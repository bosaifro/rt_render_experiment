package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.FluidInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FluidContactTest {
    static SceneStore.Revision source() {
        var source=FluidVolumeCompilerTest.source(Set.of(new FluidVolumeCompilerTest.Cell(0,0,-2)),false);
        var g=source.geometry().getFirst().input();var cell=g.fluids().getFirst();
        var neighbors=new ArrayList<>(cell.neighbors());neighbors.set(0,new FluidInputs.FaceCoverage(List.of(new FluidInputs.Rectangle(0,0,1,1)),0));
        var next=new FluidInputs.Cell(cell.part(),cell.ordinal(),cell.position(),cell.neighborhood(),cell.candidateFaces(),cell.overlayFaces(),cell.flowX(),cell.flowZ(),cell.tint(),
            cell.still(),cell.flowing(),cell.overlay(),neighbors,cell.volume());
        var fluid=new SceneCompiler().compile(new SceneInputs.Geometry(g.key(),g.revision(),g.origin(),g.current(),g.previous(),false,g.motion(),g.participation(),g.primitives(),List.of(next)));
        var floor=new FluidCompiler().compile(cell).stream().filter(p->p.ordinal()%6==0).findFirst().orElseThrow();
        var corners=new ArrayList<>(floor.corners());Collections.reverse(corners);var key=new SceneInputs.Key(5500,1);var neutral=new SceneInputs.Key(0,0);
        var surface=new SceneInputs.Surface(key,1,0,neutral,neutral,SceneInputs.Coverage.OPAQUE,0,true,0,0,0,MaterialInputs.Layers.plain(neutral),SceneInputs.HostOcclusion.BLOCK);
        var solid=new SceneCompiler().compile(new SceneInputs.Geometry(key,g.revision(),g.origin(),g.current(),g.previous(),false,g.motion(),g.participation(),
            List.of(new SceneInputs.Primitive(key,0,surface,corners))));
        return JoinedModelVolumesTest.revision(List.of(fluid,solid));
    }
    @Test void preparedFluidFloorClosesAgainstActualOpaqueSurfaceWithoutGeneratingFaces() {
        var source=source();var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,source));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());assertEquals(1,result.report().fluidRegions());
        assertEquals(SceneInputs.Boundary.OPAQUE_CONTACT,result.scene().revision().geometry().getLast().parts().getFirst().surface().boundary());
        for(int i=0;i<source.geometry().size();i++) {
            var a=source.geometry().get(i);var b=result.scene().revision().geometry().get(i);
            assertSame(a.input(),b.input());assertEquals(-1,a.positions().mismatch(b.positions()));assertEquals(-1,a.corners().mismatch(b.corners()));assertEquals(-1,a.indices().mismatch(b.indices()));
        }
        var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        var frame=media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5)));
        assertEquals(1,frame.initialMedia().orElseThrow().enclosures().size());
    }
    @Test void missingOpaqueFloorMaterialChangeAndSameCountMovementInvalidateTheWholeDomain() {
        var source=source();var compiler=new ModelVolumeCompiler();var accepted=compiler.prepare(FluidVolumeCompilerTest.frame(1,source));
        for(int mode=0;mode<4;mode++) {
            var meshes=new ArrayList<>(source.geometry());var input=meshes.getLast().input();
            if(mode==0)meshes.removeLast();
            else {
                var primitive=input.primitives().getFirst();var s=primitive.surface();var corners=new ArrayList<>(primitive.corners());
                if(mode==1)corners.replaceAll(c->new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x(),c.position().y()-.01f,c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent()));
                var surface=mode==2?new SceneInputs.Surface(s.key(),25,0,s.colorResource(),s.emissionResource(),s.coverage(),0,true,0,0,0,s.layers(),s.hostOcclusion()):s;
                var primitives=new ArrayList<SceneInputs.Primitive>();primitives.add(new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),surface,corners));
                if(mode==3)primitives.add(new SceneInputs.Primitive(primitive.part(),1,surface,corners));
                meshes.set(1,new SceneCompiler().compile(new SceneInputs.Geometry(input.key(),input.revision(),input.origin(),input.current(),input.previous(),false,input.motion(),input.participation(),primitives)));
            }
            var frame=FluidVolumeCompilerTest.frame(2+mode,JoinedModelVolumesTest.revision(meshes));var rejected=compiler.prepare(frame);
            assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,rejected.report().status(),rejected.report().toString());assertSame(frame,rejected.scene());
            assertEquals(accepted.scene().mediumDomain(),compiler.prepare(FluidVolumeCompilerTest.frame(10+mode,source)).scene().mediumDomain());
        }
    }
    @Test void floorContactsJoinAcrossSourcesAndLargeCoordinatesWithoutTreatingBoundsAsGeometry() {
        var original=source();
        for(double offset:new double[]{-30_000_000,30_000_000}) {
            var meshes=original.geometry().stream().map(mesh->{
                var g=mesh.input();return new SceneCompiler().compile(new SceneInputs.Geometry(g.key(),g.revision(),new SceneInputs.Origin(offset,g.origin().y(),offset),
                    g.current(),g.previous(),g.previousValid(),g.motion(),g.participation(),g.primitives(),g.fluids(),g.opticalModels()));
            }).toList();
            var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(meshes)));
            assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
            var frame=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow())
                .classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(offset+.5,64.35,offset-1.5)));
            assertEquals(1,frame.initialMedia().orElseThrow().enclosures().size());
        }
        assertEquals(ModelVolumeCompiler.Status.PRESSURE,new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(2,5,4096)).prepare(FluidVolumeCompilerTest.frame(2,original)).report().status());
    }
    @Test void boundaryDerivativesUseTheSameHeightFieldAndNeverBecomeVisibleCaps() {
        var original=FluidVolumeCompilerTest.source(Set.of(new FluidVolumeCompilerTest.Cell(0,0,0)),false).geometry().getFirst().input();
        var cell=original.fluids().getFirst();var compiler=new FluidCompiler();assertEquals(compiler.compile(cell),compiler.interfaces(cell,63));
        var noDraw=new FluidInputs.Cell(cell.part(),cell.ordinal(),cell.position(),cell.neighborhood(),0,cell.overlayFaces(),cell.flowX(),cell.flowZ(),cell.tint(),cell.still(),cell.flowing(),cell.overlay(),cell.neighbors(),cell.volume());
        assertTrue(compiler.compile(noDraw).isEmpty());assertEquals(compiler.compile(cell),compiler.interfaces(noDraw,63));
        var unqualified=new FluidInputs.Cell(cell.part(),cell.ordinal(),cell.position(),cell.neighborhood(),0,0,0,0,cell.tint(),cell.still(),cell.flowing(),cell.overlay());
        assertThrows(IllegalArgumentException.class,()->compiler.interfaces(unqualified,63));
    }
    @Test void fullAndPartialHeightWallsPublishDifferentBoundaryRepresentations() {
        var origin=new SceneInputs.Origin(0,64,0);
        var column=FluidContactFixtures.scene(true,true,false,origin);
        var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,column));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        assertEquals(2,result.report().fluidCells());assertEquals(1,result.report().fluidRegions());
        assertEquals(2,result.scene().revision().geometry().stream().flatMap(m->m.parts().stream()).filter(p->p.surface().boundary()==SceneInputs.Boundary.OPAQUE_CONTACT).count());
        var partial=FluidVolumeCompilerTest.frame(2,FluidContactFixtures.scene(false,true,false,origin));
        var prepared=new ModelVolumeCompiler().prepare(partial);assertEquals(ModelVolumeCompiler.Status.READY,prepared.report().status(),prepared.report().toString());
        assertEquals(1,prepared.scene().revision().geometry().stream().mapToInt(m->m.clipContacts().size()).sum());
        var wall=prepared.scene().revision().geometry().get(1).parts().stream().filter(p->p.key().low()==2).findFirst().orElseThrow();
        assertEquals(SceneInputs.Boundary.UNQUALIFIED,wall.surface().boundary(),"Partial water must not annotate the entire taller wall");
    }
    @Test void modelAndFluidContactsSharePublicationWithoutMergingTheirMedia() {
        var meshes=new ArrayList<>(OpaqueContactTest.source().geometry());meshes.addAll(source().geometry());
        var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(meshes)));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        assertEquals(1,result.report().volumes());assertEquals(1,result.report().fluidRegions());assertEquals(2,result.scene().mediumDomain().orElseThrow().volumes().size());
        var contacts=result.scene().revision().geometry().stream().flatMap(m->m.parts().stream()).filter(p->p.surface().boundary()==SceneInputs.Boundary.OPAQUE_CONTACT).toList();
        assertEquals(2,contacts.size());assertNotEquals(contacts.getFirst().surface().medium(),contacts.getLast().surface().medium());
    }
    @Test void oneOpaqueSurfaceCannotSilentlyCloseTwoIndependentProducers() {
        var source=source();var fluid=source.geometry().getFirst().input();var cell=fluid.fluids().getFirst();
        var all=new FluidCompiler().interfaces(cell,63);
        var model=new SceneInputs.Geometry(new SceneInputs.Key(6005,1),fluid.revision(),fluid.origin(),fluid.current(),fluid.previous(),false,fluid.motion(),fluid.participation(),
            all.stream().filter(p->p.ordinal()%6!=0).toList(),List.of(),List.of(new SceneInputs.OpticalModel(cell.part(),all,Set.of(0L))));
        var meshes=new ArrayList<>(source.geometry());meshes.add(new SceneCompiler().compile(model));
        var frame=FluidVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(meshes));var result=new ModelVolumeCompiler().prepare(frame);
        assertSame(frame,result.scene());assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,result.report().status());
    }
}
