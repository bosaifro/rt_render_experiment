package dev.rt_render_experiment.engine;

import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OpaqueContactTest {
    static SceneStore.Revision source() {
        var origin=JoinedModelVolumesTest.ORIGIN;var pose=SceneInputs.Transform.identity();
        var glass=JoinedModelVolumesTest.cell(1,new JoinedModelVolumesTest.Cell(0,0,0),Set.of(0L),origin,pose);
        var neighbor=JoinedModelVolumesTest.cell(2,new JoinedModelVolumesTest.Cell(1,0,0),Set.of(),origin,pose);
        var faces=neighbor.primitives().stream().map(p->{
            var s=p.surface();return new SceneInputs.Primitive(p.part(),p.ordinal(),new SceneInputs.Surface(s.key(),1,0,s.colorResource(),s.emissionResource(),
                SceneInputs.Coverage.OPAQUE,0,s.doubleSided(),0,0,0,MaterialInputs.Layers.plain(s.colorResource()),SceneInputs.HostOcclusion.BLOCK),p.corners());
        }).toList();
        var solid=new SceneInputs.Geometry(neighbor.key(),neighbor.revision(),neighbor.origin(),pose,pose,false,neighbor.motion(),neighbor.participation(),faces);
        var compiler=new SceneCompiler();return JoinedModelVolumesTest.revision(List.of(compiler.compile(glass),compiler.compile(solid)));
    }
    @Test void completeHiddenGlassFaceClosesAgainstItsActualOpaqueNeighbor() {
        var source=source();var compiler=new ModelVolumeCompiler();var result=compiler.prepare(ModelVolumeCompilerTest.frame(1,source));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        assertEquals(1,result.report().volumes());assertEquals(2,result.report().derivedMeshes());
        for(int i=0;i<2;i++) {
            var a=source.geometry().get(i);var b=result.scene().revision().geometry().get(i);
            assertSame(a.input(),b.input());assertEquals(-1,a.positions().mismatch(b.positions()));assertEquals(-1,a.indices().mismatch(b.indices()));assertEquals(-1,a.corners().mismatch(b.corners()));
            assertEquals(a.parts().stream().map(p->p.surface().material()).toList(),b.parts().stream().map(p->p.surface().material()).toList());
        }
        var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.5,.5))).initialMedia().orElseThrow().enclosures().size());
        assertEquals(0,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1.5,64.5,.5))).initialMedia().orElseThrow().enclosures().size());
        assertTrue(compiler.prepare(ModelVolumeCompilerTest.frame(2,source)).report().reused());
    }
    @Test void opaqueGenerationUnloadDuplicatesAndAppearanceInvalidateContactPublication() {
        var source=source();var compiler=new ModelVolumeCompiler();var accepted=compiler.prepare(ModelVolumeCompilerTest.frame(1,source));
        for(int mode=0;mode<5;mode++) {
            var meshes=new java.util.ArrayList<>(source.geometry());var original=meshes.getLast().input();
            if(mode==0)meshes.removeLast();
            else {
                var faces=new java.util.ArrayList<>(original.primitives());
                if(mode==1)faces.removeIf(p->p.ordinal()==1);
                else if(mode==2) {
                    var p=faces.get(1);faces.add(new SceneInputs.Primitive(p.part(),99,p.surface(),p.corners()));
                } else if(mode==3) {
                    var p=faces.get(1);var s=p.surface();faces.set(1,new SceneInputs.Primitive(p.part(),p.ordinal(),new SceneInputs.Surface(s.key(),25,0,s.colorResource(),s.emissionResource(),s.coverage(),0,true,0,0,0,s.layers(),s.hostOcclusion()),p.corners()));
                } else {
                    var p=faces.get(1);var corners=p.corners().stream().map(c->new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x()+.01f,c.position().y(),c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList();
                    faces.set(1,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners));
                }
                meshes.set(1,new SceneCompiler().compile(new SceneInputs.Geometry(original.key(),original.revision(),original.origin(),original.current(),original.previous(),false,original.motion(),original.participation(),faces)));
            }
            var request=ModelVolumeCompilerTest.frame(2+mode,JoinedModelVolumesTest.revision(meshes));var rejected=compiler.prepare(request);
            assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,rejected.report().status(),"mode="+mode+" "+rejected.report());assertSame(request,rejected.scene());
            assertEquals(accepted.scene().mediumDomain(),compiler.prepare(ModelVolumeCompilerTest.frame(20+mode,source)).scene().mediumDomain());
        }
    }
    @Test void explicitContactMaterialAndGeometryContractsAreCheckedBeforeGpuWork() {
        var source=source();var result=new ModelVolumeCompiler().prepare(ModelVolumeCompilerTest.frame(1,source));
        var solid=result.scene().revision().geometry().getLast();var contact=solid.parts().stream().filter(p->p.surface().boundary()==SceneInputs.Boundary.OPAQUE_CONTACT).findFirst().orElseThrow();
        assertEquals(1,contact.surface().medium());assertEquals(SceneInputs.Coverage.OPAQUE,contact.surface().coverage());
        var changed=new java.util.ArrayList<>(source.geometry());var input=changed.getLast().input();var faces=new java.util.ArrayList<>(input.primitives());
        var p=faces.get(1);var corners=new java.util.ArrayList<>(p.corners());var c=corners.getFirst();corners.set(0,new SceneInputs.Corner(c.position(),c.u(),c.v(),c.tint(),new SceneInputs.Vec3(1,.1f,0),c.tangent()));
        faces.set(1,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners));
        changed.set(1,new SceneCompiler().compile(new SceneInputs.Geometry(input.key(),input.revision(),input.origin(),input.current(),input.previous(),false,input.motion(),input.participation(),faces)));
        assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,new ModelVolumeCompiler().prepare(ModelVolumeCompilerTest.frame(2,JoinedModelVolumesTest.revision(changed))).report().status());
        assertEquals(ModelVolumeCompiler.Status.PRESSURE,new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(2,6,4096)).prepare(ModelVolumeCompilerTest.frame(3,source)).report().status());
        var s=p.surface();assertThrows(IllegalArgumentException.class,()->new SceneInputs.Surface(s.key(),s.material(),0,s.colorResource(),s.emissionResource(),SceneInputs.Coverage.CUTOUT,.5f,true,1,0,0,s.layers(),s.hostOcclusion(),SceneInputs.Boundary.OPAQUE_CONTACT));
    }
    @Test void entirelyHiddenModelUsesContactOnlyClosureAndDefinitionPublication() {
        var pose=SceneInputs.Transform.identity();var glass=JoinedModelVolumesTest.cell(1,new JoinedModelVolumesTest.Cell(0,0,0),Set.of(0L,1L,2L,3L,4L,5L),JoinedModelVolumesTest.ORIGIN,pose);
        var faces=glass.opticalModels().getFirst().surfaces().stream().map(p->{
            var corners=new java.util.ArrayList<>(p.corners());java.util.Collections.reverse(corners);var s=p.surface();
            return new SceneInputs.Primitive(new SceneInputs.Key(3000,p.ordinal()),0,new SceneInputs.Surface(s.key(),1,0,s.colorResource(),s.emissionResource(),
                SceneInputs.Coverage.OPAQUE,0,true,0,0,0,s.layers(),SceneInputs.HostOcclusion.BLOCK),corners);
        }).toList();
        var wall=new SceneInputs.Geometry(new SceneInputs.Key(3000,9),glass.revision(),glass.origin(),pose,pose,false,glass.motion(),glass.participation(),faces);
        var compiler=new SceneCompiler();var source=JoinedModelVolumesTest.revision(List.of(compiler.compile(glass),compiler.compile(wall)));
        var result=new ModelVolumeCompiler().prepare(ModelVolumeCompilerTest.frame(1,source));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());assertEquals(1,result.report().derivedMeshes());
        assertEquals(0,result.scene().revision().geometry().getFirst().triangleCount());
        assertTrue(result.scene().revision().geometry().getLast().parts().stream().allMatch(p->p.surface().boundary()==SceneInputs.Boundary.OPAQUE_CONTACT));
        var domain=result.scene().mediumDomain().orElseThrow();var frame=new SceneMediaCompiler().prepare(result.scene().revision(),domain)
            .classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.5,.5)));
        InitialMedia.validate(frame,1,1,result.scene().revision().geometry());assertEquals(1,frame.initialMedia().orElseThrow().enclosures().size());
        var definitions=MediumDefinitions.prepare(domain,result.scene().revision().geometry(),null);definitions.requireFrameOrigin(frame);
        assertEquals(48,definitions.bytes().remaining());
    }
}
