package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.FluidInputs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PartialContactTest {
    @Test void waterBelowAWholeWallPublishesOnlyItsActualMediumExtent() {
        var source=FluidContactFixtures.scene(false,true,false,new SceneInputs.Origin(0,64,0));
        var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,source));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        assertEquals(1,result.report().fluidRegions());
        for(int i=0;i<source.geometry().size();i++) {
            var before=source.geometry().get(i);var after=result.scene().revision().geometry().get(i);
            assertSame(before.input(),after.input());assertEquals(-1,before.positions().mismatch(after.positions()));
            assertEquals(-1,before.corners().mismatch(after.corners()));assertEquals(-1,before.indices().mismatch(after.indices()));
        }
        var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.35,-1.5))).initialMedia().orElseThrow().enclosures().size());
        assertEquals(0,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.95,-1.5))).initialMedia().orElseThrow().enclosures().size());
        var wall=result.scene().revision().geometry().get(1);
        assertEquals(1,wall.clipContacts().size());assertEquals(wall.triangleCount()+2,wall.traceTriangleCount());
        assertEquals(-1,wall.positions().mismatch(wall.tracePositions().slice(0,wall.positions().remaining())));
        assertEquals(-1,wall.corners().mismatch(wall.traceCorners().slice(0,wall.corners().remaining())));
        assertEquals(-1,wall.indices().mismatch(wall.traceIndices().slice(0,wall.indices().remaining())));
        assertSame(source.geometry().get(1).findPart(new SceneInputs.Key(5600,2),0).orElseThrow(),wall.findPart(new SceneInputs.Key(5600,2),0).orElseThrow());
    }
    @Test void sourceOwnerRemovalSameCountMovementAndPressureRefuseTheWholeCandidate() {
        var source=FluidContactFixtures.scene(false,true,false,new SceneInputs.Origin(0,64,0));var compiler=new ModelVolumeCompiler();
        var first=compiler.prepare(FluidVolumeCompilerTest.frame(1,source));var g=source.geometry().get(1).input();
        for(int mode=0;mode<3;mode++) {
            var primitives=new ArrayList<>(g.primitives());var p=primitives.get(1);
            if(mode==0)primitives.remove(1);
            if(mode==1)primitives.set(1,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),p.corners().stream().map(c->
                new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x()+.01f,c.position().y(),c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList()));
            if(mode==2)primitives.add(new SceneInputs.Primitive(p.part(),99,p.surface(),p.corners()));
            var meshes=new ArrayList<>(source.geometry());meshes.set(1,TiledContactTest.compile(g,primitives));
            var input=FluidVolumeCompilerTest.frame(2+mode,JoinedModelVolumesTest.revision(meshes));var rejected=compiler.prepare(input);
            assertSame(input,rejected.scene());assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,rejected.report().status(),rejected.report().toString());
            assertEquals(first.scene().mediumDomain(),compiler.prepare(FluidVolumeCompilerTest.frame(10+mode,source)).scene().mediumDomain());
        }
        var input=FluidVolumeCompilerTest.frame(20,source);var rejected=new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(8,1000,4096,32,1)).prepare(input);
        assertSame(input,rejected.scene());assertEquals(ModelVolumeCompiler.Status.PRESSURE,rejected.report().status());
    }
    @Test void changedWaterHeightInvalidatesOnlyTheTraceDerivativeOfItsUnchangedWall() {
        var source=FluidContactFixtures.scene(false,true,false,new SceneInputs.Origin(0,64,0));var compiler=new ModelVolumeCompiler();var first=compiler.prepare(FluidVolumeCompilerTest.frame(1,source));
        var g=source.geometry().getFirst().input();var cell=g.fluids().getFirst();var samples=new ArrayList<>(cell.neighborhood());samples.set(4,new FluidInputs.Sample(.5f,true,false,false));
        var fluid=new FluidInputs.Cell(cell.part(),cell.ordinal(),cell.position(),samples,cell.candidateFaces(),cell.overlayFaces(),cell.flowX(),cell.flowZ(),cell.tint(),cell.still(),cell.flowing(),cell.overlay(),cell.neighbors(),cell.volume());
        var meshes=new ArrayList<>(source.geometry());meshes.set(0,new SceneCompiler().compile(new SceneInputs.Geometry(g.key(),g.revision(),g.origin(),g.current(),g.previous(),false,g.motion(),g.participation(),g.primitives(),List.of(fluid))));
        var second=compiler.prepare(FluidVolumeCompilerTest.frame(2,JoinedModelVolumesTest.revision(meshes)));assertEquals(ModelVolumeCompiler.Status.READY,second.report().status());assertFalse(second.report().reused());
        var a=first.scene().revision().geometry().get(1);var b=second.scene().revision().geometry().get(1);
        assertSame(a.input(),b.input());assertEquals(a.topologyHash(),b.topologyHash());assertEquals(a.appearanceHash(),b.appearanceHash());assertEquals(-1,a.positions().mismatch(b.positions()));
        assertNotEquals(a.tracePositionHash(),b.tracePositionHash());assertEquals(a.traceTriangleCount(),b.traceTriangleCount());
        assertEquals(first.scene().mediumDomain(),second.scene().mediumDomain());
    }
    @Test void aSlopedFluidEdgeRetainsItsPreparedExtentAtBothEnds() {
        var source=FluidContactFixtures.scene(false,true,false,new SceneInputs.Origin(0,64,0));var g=source.geometry().getFirst().input();var cell=g.fluids().getFirst();
        var samples=new ArrayList<>(cell.neighborhood());samples.set(1,new FluidInputs.Sample(0,false,false,true));
        var fluid=new FluidInputs.Cell(cell.part(),cell.ordinal(),cell.position(),samples,cell.candidateFaces(),cell.overlayFaces(),cell.flowX(),cell.flowZ(),cell.tint(),cell.still(),cell.flowing(),cell.overlay(),cell.neighbors(),cell.volume());
        var meshes=new ArrayList<>(source.geometry());meshes.set(0,new SceneCompiler().compile(new SceneInputs.Geometry(g.key(),g.revision(),g.origin(),g.current(),g.previous(),false,g.motion(),g.participation(),g.primitives(),List.of(fluid))));
        var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(meshes)));assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        var contact=result.scene().revision().geometry().get(1).clipContacts().getFirst();assertNotEquals(contact.corners().get(2).y(),contact.corners().get(3).y());
        var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.77,-1.95))).initialMedia().orElseThrow().enclosures().size());
        assertEquals(0,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.77,-1.05))).initialMedia().orElseThrow().enclosures().size());
    }
    @Test void partialContactCannotBeDeclaredWithoutAnActualSupportingOpaqueOwner() {
        var source=FluidContactFixtures.scene(false,true,false,new SceneInputs.Origin(0,64,0));var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,source));
        var clipped=result.scene().revision().geometry().get(1);var contact=clipped.clipContacts().getFirst();var raw=source.geometry().get(1);
        var corners=new ArrayList<>(contact.corners());corners.set(0,new SceneInputs.Vec3(1,-.1f,-2));
        assertThrows(IllegalArgumentException.class,()->new SceneCompiler().withClipContacts(raw,List.of(new ClipGeometry.Contact(contact.owner(),1,corners))));
        assertEquals(clipped.bytes()-raw.bytes(),ClipGeometry.allocationBytes(raw,List.of(contact)));
    }
    @Test void clippingTrianglesNeverCreateOrRenumberPhysicalEmissionSources() {
        var source=FluidContactFixtures.scene(false,true,false,new SceneInputs.Origin(0,64,0));var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,source));
        var a=new LightCompiler().prepare(1,source.geometry(),List.of(),new SceneInputs.Origin(0,64,0),null).compiled();
        var b=new LightCompiler().prepare(1,result.scene().revision().geometry(),List.of(),new SceneInputs.Origin(0,64,0),null).compiled();
        assertEquals(a.associations(),b.associations());assertEquals(a.worldCount(),b.worldCount());assertEquals(-1,a.sources().mismatch(b.sources()));
    }
}
