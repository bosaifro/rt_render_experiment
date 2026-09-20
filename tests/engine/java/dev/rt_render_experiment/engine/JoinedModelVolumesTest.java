package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;


final class JoinedModelVolumesTest {
    record Cell(int x,int y,int z) {}
    static final SceneInputs.Origin ORIGIN=new SceneInputs.Origin(0,64,0);
    static SceneStore.Revision scene(List<Cell> cells) { return scene(cells,ORIGIN,SceneInputs.Transform.identity()); }
    static SceneStore.Revision scene(List<Cell> cells,SceneInputs.Origin origin,SceneInputs.Transform pose) {
        var meshes=new ArrayList<SceneCompiler.Compiled>();
        for(int i=0;i<cells.size();i++) {
            var cell=cells.get(i);var hidden=new HashSet<Long>();
            for(int face=0;face<6;face++) {
                int[] point={cell.x,cell.y,cell.z};point[face/2]+=(face&1)==0?1:-1;
                if(cells.contains(new Cell(point[0],point[1],point[2])))hidden.add((long)face);
            }
            meshes.add(new SceneCompiler().compile(cell(i+1,cell,hidden,origin,pose)));
        }
        return revision(meshes);
    }
    static SceneStore.Revision revision(List<SceneCompiler.Compiled> meshes) {
        return new SceneStore.Revision(1,1,meshes,meshes.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
    static SceneInputs.Geometry cell(long id,Cell cell,Set<Long> hidden,SceneInputs.Origin origin,SceneInputs.Transform pose) {
        var key=new SceneInputs.Key(2100,id);var neutral=new SceneInputs.Key(0,0);
        var surface=new SceneInputs.Surface(key,24,0,neutral,neutral,SceneInputs.Coverage.DIELECTRIC,0,true,0,0,0,
            MaterialInputs.Layers.plain(neutral),SceneInputs.HostOcclusion.PASS);
        var faces=new ArrayList<SceneInputs.Primitive>();
        for(int face=0;face<6;face++) {
            int axis=face/2;boolean positive=(face&1)==0;int u=(axis+1)%3,v=(axis+2)%3;
            if(!positive) { int swap=u;u=v;v=swap; }
            var corners=new ArrayList<SceneInputs.Corner>();
            for(int[] sign:new int[][]{{-1,-1},{1,-1},{1,1},{-1,1}}) {
                float[] p={cell.x+.5f,cell.y+.5f,cell.z+.5f},n={0,0,0},t={0,0,0};
                p[axis]+=positive?.5f:-.5f;p[u]+=sign[0]*.5f;p[v]+=sign[1]*.5f;n[axis]=positive?1:-1;t[u]=1;
                corners.add(new SceneInputs.Corner(new SceneInputs.Vec3(p[0],p[1],p[2]),(sign[0]+1)*.5f,(sign[1]+1)*.5f,
                    new SceneInputs.Color(.8f,.8f,.8f,1),new SceneInputs.Vec3(n[0],n[1],n[2]),new SceneInputs.Vec3(t[0],t[1],t[2])));
            }
            faces.add(new SceneInputs.Primitive(key,face,surface,corners));
        }
        return new SceneInputs.Geometry(key,new SceneInputs.Revision(1,1,0,1,1,1,1),origin,pose,pose,false,SceneInputs.Motion.STATIC,
            new Participation(Participation.WORLD,true),faces.stream().filter(p->!hidden.contains(p.ordinal())).toList(),List.of(),
            List.of(new SceneInputs.OpticalModel(key,faces,hidden)));
    }
    @Test void opposingFacesJoinWithoutChangingGeometryAndInternalFaceIsNotAnOriginBoundary() {
        var source=scene(List.of(new Cell(0,0,0),new Cell(1,0,0)));var compiler=new ModelVolumeCompiler();
        var result=compiler.prepare(ModelVolumeCompilerTest.frame(1,source));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        assertEquals(1,result.report().volumes());assertEquals(20,source.geometry().stream().mapToInt(SceneCompiler.Compiled::triangleCount).sum());
        for(int i=0;i<2;i++) {
            var before=source.geometry().get(i);var after=result.scene().revision().geometry().get(i);
            assertSame(before.input(),after.input());assertEquals(-1,before.positions().mismatch(after.positions()));
            assertEquals(-1,before.corners().mismatch(after.corners()));assertEquals(-1,before.indices().mismatch(after.indices()));
            assertTrue(after.parts().stream().allMatch(p->p.surface().medium()==1));
        }
        var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        for(double x:new double[]{.5,1,1.5})assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(x,64.5,.5))).initialMedia().orElseThrow().enclosures().size());
        assertTrue(compiler.prepare(ModelVolumeCompilerTest.frame(2,source)).report().reused());
    }
    @Test void concaveUnionAndFullyOccludedCentralSourceAreIncluded() {
        var cells=new ArrayList<Cell>();for(int x=0;x<3;x++)for(int y=0;y<3;y++)for(int z=0;z<3;z++)cells.add(new Cell(x,y,z));
        for(var shape:List.of(List.of(new Cell(0,0,0),new Cell(1,0,0),new Cell(0,1,0)),cells)) {
            var source=scene(shape);var result=new ModelVolumeCompiler().prepare(ModelVolumeCompilerTest.frame(1,source));
            assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());assertEquals(1,result.report().volumes());
            var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
            for(var cell:shape)assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(cell.x+.5,64+cell.y+.5,cell.z+.5))).initialMedia().orElseThrow().enclosures().size());
        }
    }
    @Test void sourceRebasingNegativeScaleAndGroupIdentity() {
        var pose=new SceneInputs.Transform(new float[]{-2,0,0,0,0,.5f,0,0,0,0,1,0});
        var origin=new SceneInputs.Origin(30_000_000,96,-30_000_000);
        var first=cell(1,new Cell(0,0,0),Set.of(0L),origin,pose);

        var second=cell(2,new Cell(-15,0,0),Set.of(1L),new SceneInputs.Origin(origin.x()-32,96,origin.z()),pose);
        var joined=revision(List.of(new SceneCompiler().compile(first),new SceneCompiler().compile(second)));
        var compiler=new ModelVolumeCompiler();var result=compiler.prepare(ModelVolumeCompilerTest.frame(1,joined));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().toString());
        var media=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        assertEquals(1,media.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(origin.x()-2,96.25,origin.z()+.5))).initialMedia().orElseThrow().enclosures().size());
        var rt_render_experimentdered=revision(List.of(joined.geometry().getLast(),joined.geometry().getFirst()));
        assertEquals(result.scene().mediumDomain(),compiler.prepare(ModelVolumeCompilerTest.frame(2,rt_render_experimentdered)).scene().mediumDomain());
        var split=revision(List.of(new SceneCompiler().compile(cell(1,new Cell(0,0,0),Set.of(),origin,pose)),
            new SceneCompiler().compile(cell(2,new Cell(3,0,0),Set.of(),origin,pose))));
        var separated=compiler.prepare(ModelVolumeCompilerTest.frame(3,split));assertEquals(ModelVolumeCompiler.Status.READY,separated.report().status());
        assertEquals(2,separated.report().volumes());assertTrue(separated.scene().mediumDomain().orElseThrow().volumes().stream().noneMatch(v->v.identity()==1));
        var restored=compiler.prepare(ModelVolumeCompilerTest.frame(4,joined));assertNotEquals(result.scene().mediumDomain(),restored.scene().mediumDomain());
        compiler.prepare(ModelVolumeCompilerTest.frame(5,revision(List.of())));
        assertNotEquals(restored.scene().mediumDomain(),compiler.prepare(ModelVolumeCompilerTest.frame(6,joined)).scene().mediumDomain());
    }
    @Test void unmatchedAsymmetricPartialAndHeterogeneousInputsNeverPublishPartialAnnotations() {
        var source=scene(List.of(new Cell(0,0,0),new Cell(1,0,0)));var compiler=new ModelVolumeCompiler();
        var accepted=compiler.prepare(ModelVolumeCompilerTest.frame(1,source));
        for(int mode=0;mode<6;mode++) {
            var meshes=new ArrayList<>(source.geometry());var g=meshes.getLast().input();
            if(mode==0)meshes.removeLast();
            else if(mode==1)meshes.add(new SceneCompiler().compile(cell(3,new Cell(1,0,0),Set.of(1L),ORIGIN,SceneInputs.Transform.identity())));
            else {
                var faces=new ArrayList<>(g.opticalModels().getFirst().surfaces());var hidden=new HashSet<>(g.opticalModels().getFirst().occludedOrdinals());
                if(mode==2) {

                    hidden.clear();
                } else if(mode==3) {
                    for(int i=0;i<faces.size();i++) {
                        var p=faces.get(i);var corners=p.corners().stream().map(c->new SceneInputs.Corner(c.position(),c.u(),c.v(),new SceneInputs.Color(.7f,.8f,.8f,1),c.normal(),c.tangent())).toList();
                        faces.set(i,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners));
                    }
                } else if(mode==4) {
                    var p=faces.get(1);var corners=new ArrayList<>(p.corners());java.util.Collections.reverse(corners);
                    faces.set(1,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners));
                } else {
                    var p=faces.remove(1);hidden.clear();hidden.add(10L);hidden.add(11L);
                    faces.add(new SceneInputs.Primitive(p.part(),10,p.surface(),p.corners().subList(0,3)));


                    var a=p.corners().get(0);var b=p.corners().get(3);var x=a.position();var y=b.position();
                    var midpoint=new SceneInputs.Corner(new SceneInputs.Vec3((x.x()+y.x())*.5f,(x.y()+y.y())*.5f,(x.z()+y.z())*.5f),a.u(),a.v(),a.tint(),a.normal(),a.tangent());
                    faces.add(new SceneInputs.Primitive(p.part(),11,p.surface(),List.of(p.corners().get(2),p.corners().get(3),midpoint)));
                }
                var changed=new SceneInputs.Geometry(g.key(),g.revision(),g.origin(),g.current(),g.previous(),false,g.motion(),g.participation(),
                    faces.stream().filter(p->!hidden.contains(p.ordinal())).toList(),List.of(),List.of(new SceneInputs.OpticalModel(g.key(),faces,hidden)));
                meshes.set(1,new SceneCompiler().compile(changed));
            }
            var frame=ModelVolumeCompilerTest.frame(2+mode,revision(meshes));var rejected=compiler.prepare(frame);
            assertEquals(ModelVolumeCompiler.Status.HIDDEN_INTERFACE,rejected.report().status(),"mode="+mode+" "+rejected.report());
            assertSame(frame,rejected.scene());assertTrue(rejected.scene().mediumDomain().isEmpty());
            assertTrue(compiler.prepare(frame).report().reused());
            assertEquals(accepted.scene().mediumDomain(),compiler.prepare(ModelVolumeCompilerTest.frame(20+mode,source)).scene().mediumDomain());
        }
    }
    @Test void hiddenGeometryAndCertificationAreBothBudgeted() {
        var source=scene(List.of(new Cell(0,0,0),new Cell(1,0,0)));

        assertEquals(ModelVolumeCompiler.Status.PRESSURE,new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(2,11,4096))
            .prepare(ModelVolumeCompilerTest.frame(1,source)).report().status());
        var definition=MediumProfiles.definition(dev.rt_render_experiment.contract.MediumInputs.Kind.GLASS,new SceneInputs.Vec3(.8f,.8f,.8f));
        var candidates=source.geometry().stream().map(g->new ModelVolumeJoins.Model(g,g.modelBoundaries().getFirst(),definition)).toList();
        assertThrows(IllegalStateException.class,()->ModelVolumeJoins.prepare(candidates,new ConvexVolume.Budget(10)));
    }
}
