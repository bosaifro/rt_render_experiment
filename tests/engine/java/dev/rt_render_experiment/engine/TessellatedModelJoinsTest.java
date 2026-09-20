package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TessellatedModelJoinsTest {
    static SceneInputs.Geometry retile(SceneInputs.Geometry g,int mode) {
        if(mode==0 || g.opticalModels().isEmpty())return g;
        var model=g.opticalModels().getFirst();var faces=new ArrayList<SceneInputs.Primitive>();var hidden=new HashSet<Long>();
        for(var p:model.surfaces())if(model.occludedOrdinals().contains(p.ordinal())) {
            for(var tile:tiles(p,mode)) {
                long ordinal=100+p.ordinal()*8+tile.ordinal();
                faces.add(new SceneInputs.Primitive(p.part(),ordinal,p.surface(),tile.corners()));hidden.add(ordinal);
            }
        } else faces.add(p);
        return new SceneInputs.Geometry(g.key(),g.revision(),g.origin(),g.current(),g.previous(),g.previousValid(),g.motion(),g.participation(),g.primitives(),g.fluids(),
            List.of(new SceneInputs.OpticalModel(model.part(),faces,hidden)));
    }
    static List<SceneInputs.Primitive> tiles(SceneInputs.Primitive p,int mode) {
        if(mode<=2)return TiledContactTest.tiles(p,mode-1);
        if(mode==5)return TiledContactTest.tiles(p,2);
        var c=p.corners();int start=mode==3?0:1;
        var a=c.get(start);var b=c.get((start+1)%4);var d=c.get((start+2)%4);var e=c.get((start+3)%4);
        var x=mid(a,b);var y=mid(d,e);
        return List.of(new SceneInputs.Primitive(p.part(),0,p.surface(),List.of(a,x,y,e)),new SceneInputs.Primitive(p.part(),1,p.surface(),List.of(x,b,d,y)));
    }
    private static SceneInputs.Corner mid(SceneInputs.Corner a,SceneInputs.Corner b) {
        var p=a.position();var q=b.position();return new SceneInputs.Corner(new SceneInputs.Vec3((p.x()+q.x())*.5f,(p.y()+q.y())*.5f,(p.z()+q.z())*.5f),
            (a.u()+b.u())*.5f,(a.v()+b.v())*.5f,a.tint(),a.normal(),a.tangent());
    }
    static SceneStore.Revision source(int a,int b) {
        var original=JoinedModelVolumesTest.scene(List.of(new JoinedModelVolumesTest.Cell(0,0,0),new JoinedModelVolumesTest.Cell(1,0,0)));
        return JoinedModelVolumesTest.revision(List.of(new SceneCompiler().compile(retile(original.geometry().getFirst().input(),a)),new SceneCompiler().compile(retile(original.geometry().getLast().input(),b))));
    }
    @Test void differentHiddenTriangleAndQuadSubdivisionsPreserveOnePhysicalRegion() {
        for(int a=0;a<=5;a++)for(int b=0;b<=5;b++) {
            var source=source(a,b);var input=ModelVolumeCompilerTest.frame(1,source);var result=new ModelVolumeCompiler().prepare(input);
            assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),a+"/"+b+" "+result.report());assertEquals(1,result.report().volumes());
            for(int i=0;i<2;i++) {
                var before=source.geometry().get(i);var after=result.scene().revision().geometry().get(i);assertSame(before.input(),after.input());
                assertEquals(-1,before.positions().mismatch(after.positions()));assertEquals(-1,before.corners().mismatch(after.corners()));assertEquals(-1,before.indices().mismatch(after.indices()));
            }
            var classified=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
            for(double x:new double[]{.5,1,1.5})assertEquals(1,classified.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(x,64.5,.5))).initialMedia().orElseThrow().enclosures().size());
        }
    }
    @Test void incompleteDuplicateAndOneUlpCoversNeverPublishPartialJoins() {
        var base=source(0,1);var g=base.geometry().getLast().input();var compiler=new ModelVolumeCompiler();
        var accepted=compiler.prepare(ModelVolumeCompilerTest.frame(1,base));
        for(int mode=0;mode<4;mode++) {
            var m=g.opticalModels().getFirst();var faces=new ArrayList<>(m.surfaces());var hidden=new HashSet<>(m.occludedOrdinals());
            var p=faces.stream().filter(f->hidden.contains(f.ordinal())).findFirst().orElseThrow();
            if(mode==0) { faces.remove(p);hidden.remove(p.ordinal()); }
            if(mode==1) { faces.add(new SceneInputs.Primitive(p.part(),999,p.surface(),p.corners()));hidden.add(999L); }
            if(mode>=2) {
                var corners=new ArrayList<>(p.corners());var c=corners.getFirst();
                if(mode==2)java.util.Collections.reverse(corners);
                else corners.set(0,new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x(),Math.nextUp(c.position().y()),c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent()));
                faces.set(faces.indexOf(p),new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners));
            }
            var changed=new SceneInputs.Geometry(g.key(),g.revision(),g.origin(),g.current(),g.previous(),false,g.motion(),g.participation(),g.primitives(),g.fluids(),List.of(new SceneInputs.OpticalModel(m.part(),faces,hidden)));
            var input=ModelVolumeCompilerTest.frame(2+mode,JoinedModelVolumesTest.revision(List.of(base.geometry().getFirst(),new SceneCompiler().compile(changed))));
            var failed=compiler.prepare(input);assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,failed.report().status(),mode+" "+failed.report());assertSame(input,failed.scene());
            assertEquals(accepted.scene().mediumDomain(),compiler.prepare(ModelVolumeCompilerTest.frame(10+mode,base)).scene().mediumDomain());
        }
    }
    @Test void hiddenOnlyTopologyRevisionRevalidatesWithoutChangingRegionIdentity() {
        var compiler=new ModelVolumeCompiler();var first=compiler.prepare(ModelVolumeCompilerTest.frame(1,source(1,1)));
        var input=source(2,2);var second=compiler.prepare(ModelVolumeCompilerTest.frame(2,input));
        assertEquals(ModelVolumeCompiler.Status.READY,second.report().status());assertFalse(second.report().reused());assertEquals(first.scene().mediumDomain(),second.scene().mediumDomain());
        for(int i=0;i<2;i++)assertEquals(-1,first.scene().revision().geometry().get(i).positions().mismatch(second.scene().revision().geometry().get(i).positions()));
        assertTrue(compiler.prepare(ModelVolumeCompilerTest.frame(3,input)).report().reused());
    }
    @Test void oneLargeSourceFaceCanJoinTwoDistinctSmallerModelCells() {
        var pose=SceneInputs.Transform.identity();var origin=JoinedModelVolumesTest.ORIGIN;
        var a=JoinedModelVolumesTest.cell(1,new JoinedModelVolumesTest.Cell(0,0,0),Set.of(0L),origin,pose);
        var b=half(JoinedModelVolumesTest.cell(2,new JoinedModelVolumesTest.Cell(1,0,0),Set.of(1L,2L),origin,pose),0);
        var c=half(JoinedModelVolumesTest.cell(3,new JoinedModelVolumesTest.Cell(1,0,0),Set.of(1L,3L),origin,pose),.5f);
        var scene=JoinedModelVolumesTest.revision(List.of(new SceneCompiler().compile(a),new SceneCompiler().compile(b),new SceneCompiler().compile(c)));
        var result=new ModelVolumeCompiler().prepare(ModelVolumeCompilerTest.frame(1,scene));assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        assertEquals(1,result.report().volumes());var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        for(double y:new double[]{.25,.5,.75})for(double x:new double[]{.5,1,1.5})assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(x,64+y,.5))).initialMedia().orElseThrow().enclosures().size());
    }
    private static SceneInputs.Geometry half(SceneInputs.Geometry g,float offset) {
        var m=g.opticalModels().getFirst();var faces=m.surfaces().stream().map(p->new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),p.corners().stream().map(c->
            new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x(),c.position().y()*.5f+offset,c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList())).toList();
        return new SceneInputs.Geometry(g.key(),g.revision(),g.origin(),g.current(),g.previous(),false,g.motion(),g.participation(),faces.stream().filter(p->!m.occludedOrdinals().contains(p.ordinal())).toList(),List.of(),List.of(new SceneInputs.OpticalModel(m.part(),faces,m.occludedOrdinals())));
    }
    @Test void endpointGraphIsNotPermissionToAcceptDisconnectedOrOverlappingVolumes() {
        var prototype=source(5,0).geometry().getFirst().input();assertTrue(new SceneCompiler().compile(prototype).modelBoundaries().getFirst().components().size()>1);
        var pose=SceneInputs.Transform.identity();var first=JoinedModelVolumesTest.cell(1,new JoinedModelVolumesTest.Cell(0,0,0),Set.of(),JoinedModelVolumesTest.ORIGIN,pose);
        for(int offset:new int[]{0,3}) {
            var next=JoinedModelVolumesTest.cell(2,new JoinedModelVolumesTest.Cell(offset,0,0),Set.of(),JoinedModelVolumesTest.ORIGIN,pose);
            var all=new ArrayList<>(first.primitives());
            for(var p:next.primitives())all.add(new SceneInputs.Primitive(first.key(),100+p.ordinal(),p.surface(),p.corners()));
            var g=new SceneInputs.Geometry(first.key(),first.revision(),first.origin(),pose,pose,false,first.motion(),first.participation(),all,List.of(),List.of(new SceneInputs.OpticalModel(first.key(),all,Set.of())));
            var frame=ModelVolumeCompilerTest.frame(1,JoinedModelVolumesTest.revision(List.of(new SceneCompiler().compile(g))));var result=new ModelVolumeCompiler().prepare(frame);
            assertSame(frame,result.scene());assertEquals(ModelVolumeCompiler.Status.GEOMETRY_UNSUPPORTED,result.report().status(),result.report().toString());
        }
    }
    @Test void expandedSourceFacesRemainSubjectToPreparationLimits() {
        var input=ModelVolumeCompilerTest.frame(1,source(5,5));var result=new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(2,15,4096)).prepare(input);
        assertEquals(ModelVolumeCompiler.Status.PRESSURE,result.report().status());assertSame(input,result.scene());
    }
}
