package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SceneMediaCompilerTest {
    static MediumInputs.Domain domain() {
        return new MediumInputs.Domain(GlassFixtures.glass().stream().map(e->new MediumInputs.Volume(e.volume(),e.medium())).toList());
    }
    @Test void automaticOriginsAndCache() {
        var source=OrientedMediumFixtures.scene(false,0);var compiler=new SceneMediaCompiler();
        var first=compiler.prepare(source,domain());assertEquals(2,first.work().compiledVolumes());assertEquals(24,first.work().triangles());
        var inside=first.classify(InitialMediaFixtures.frame()).initialMedia().orElseThrow();
        assertEquals(GlassFixtures.glass(),inside.enclosures());assertTrue(inside.clearance()>.49 && inside.clearance()<.5);
        var outside=first.classify(SceneFixtures.frame()).initialMedia().orElseThrow();assertTrue(outside.enclosures().isEmpty());
        var outer=first.classify(at(new SceneInputs.Origin(0,64,-1.5))).initialMedia().orElseThrow();assertEquals(GlassFixtures.glass().subList(0,1),outer.enclosures());
        var same=new SceneStore.Revision(2,1,source.geometry(),source.bytes());var reused=compiler.prepare(same,domain());
        assertEquals(0,reused.work().compiledVolumes());assertEquals(2,reused.work().reusedVolumes());
        assertEquals(2,reused.classify(InitialMediaFixtures.frame()).initialMedia().orElseThrow().sceneRevision());
        assertThrows(IllegalArgumentException.class,()->first.classify(at(new SceneInputs.Origin(0,64,-3))));
        var shallow=first.classify(at(new SceneInputs.Origin(0,64,-2.999)));
        assertDoesNotThrow(()->InitialMedia.validate(shallow,1,1,source.geometry()),"A complete certified domain must support the clipped interface prefix");
        assertThrows(IllegalArgumentException.class,()->first.classify(InitialMediaFixtures.frame().withInitialMedia(
            new MediumInputs.Origin(1,1,InitialMediaFixtures.frame().eye(),.4f,List.of()))));
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(source,new MediumInputs.Domain(List.of(domain().volumes().getFirst()))));
        assertEquals(2,compiler.prepare(same,domain()).work().reusedVolumes(),"Failed compilation replaced the coherent cache");
        compiler.clear();assertEquals(2,compiler.prepare(source,domain()).work().compiledVolumes());
        assertThrows(IllegalStateException.class,()->new SceneMediaCompiler(new SceneMediaCompiler.Limits(1,100)).prepare(source,domain()));
        assertThrows(IllegalStateException.class,()->new SceneMediaCompiler(new SceneMediaCompiler.Limits(2,10)).prepare(source,domain()));
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(new SceneStore.Revision(3,2,source.geometry(),source.bytes()),domain()));
    }
    @Test void transformsAndLargeCoordinates() {
        var matrix=new Matrix4f().rotateY(.63f).rotateZ(-.27f).scale(-2,.7f,1.3f);var pose=transform(matrix);
        var world=new SceneInputs.Origin(30_000_000,96,-30_000_000);var source=transformed(world,pose);
        var location=new org.joml.Vector3d(0,0,-2.5);
        double x=world.x()+pose.get(0,0)*location.x+pose.get(0,1)*location.y+pose.get(0,2)*location.z+pose.get(0,3);
        double y=world.y()+pose.get(1,0)*location.x+pose.get(1,1)*location.y+pose.get(1,2)*location.z+pose.get(1,3);
        double z=world.z()+pose.get(2,0)*location.x+pose.get(2,1)*location.y+pose.get(2,2)*location.z+pose.get(2,3);
        var frame=at(new SceneInputs.Origin(x,y,z));
        var classified=new SceneMediaCompiler().prepare(source,domain()).classify(frame);
        assertEquals(GlassFixtures.glass(),classified.initialMedia().orElseThrow().enclosures());
        assertTrue(classified.initialMedia().orElseThrow().clearance()>.6);
        InitialMedia.validate(classified,1,1,source.geometry());
    }
    @Test void malformedShapesAndOverlapAreNotAir() {
        var scene=OrientedMediumFixtures.scene(false,0);var outer=scene.geometry().getFirst().input();
        for(int mode=0;mode<4;mode++) {
            var primitives=new ArrayList<>(outer.primitives());
            if(mode==0)primitives.removeLast();
            if(mode==1) { var p=primitives.getFirst();var corners=new ArrayList<>(p.corners());java.util.Collections.reverse(corners);primitives.set(0,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners)); }
            if(mode==2) { var p=primitives.getFirst();primitives.add(new SceneInputs.Primitive(p.part(),99,p.surface(),p.corners())); }
            if(mode==3) { var p=primitives.getFirst();var corners=new ArrayList<>(p.corners());var c=corners.getFirst();corners.set(0,new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x()+.01f,c.position().y(),c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent()));primitives.set(0,new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners)); }
            var g=new SceneCompiler().compile(copy(outer,outer.origin(),outer.current(),primitives));
            var altered=new SceneStore.Revision(1,1,List.of(g,scene.geometry().get(1)),g.bytes()+scene.geometry().get(1).bytes());
            assertThrows(IllegalArgumentException.class,()->new SceneMediaCompiler().prepare(altered,domain()));
        }
        var inner=scene.geometry().get(1).input();var overlap=SceneInputs.Transform.identity().rows();overlap[3]=32;
        var changed=new SceneCompiler().compile(copy(inner,inner.origin(),new SceneInputs.Transform(overlap),inner.primitives()));
        assertThrows(IllegalArgumentException.class,()->new SceneMediaCompiler().prepare(new SceneStore.Revision(1,1,List.of(scene.geometry().getFirst(),changed),0),domain()));
        var unqualified=ModelBoundaryTest.cube();var g=new SceneCompiler().compile(copy(outer,outer.origin(),outer.current(),unqualified));
        assertThrows(IllegalArgumentException.class,()->new SceneMediaCompiler().prepare(new SceneStore.Revision(1,1,List.of(g),g.bytes()),new MediumInputs.Domain(List.of())));
    }
    @Test void tiledFacesAndMultipleSourceMeshes() {
        var original=OrientedMediumFixtures.scene(false,0);var outer=original.geometry().getFirst().input();
        var meshes=new ArrayList<SceneCompiler.Compiled>();int key=0;
        for(var face:outer.primitives()) {
            var corners=face.corners();SceneInputs.Corner a=corners.get(0),b=corners.get(1),c=corners.get(2),d=corners.get(3);
            SceneInputs.Corner ab=mid(a,b),cd=mid(d,c);
            for(var tile:List.of(List.of(a,ab,cd,d),List.of(ab,b,c,cd))) {
                double offset=(key%3)*16;var local=tile.stream().map(vertex->new SceneInputs.Corner(
                    new SceneInputs.Vec3(vertex.position().x()-(float)offset,vertex.position().y(),vertex.position().z()),vertex.u(),vertex.v(),vertex.tint(),vertex.normal(),vertex.tangent())).toList();
                var sourceOrigin=new SceneInputs.Origin(outer.origin().x()+offset,outer.origin().y(),outer.origin().z());
                var input=new SceneInputs.Geometry(new SceneInputs.Key(881,++key),outer.revision(),sourceOrigin,outer.current(),outer.previous(),false,outer.motion(),outer.participation(),
                    List.of(new SceneInputs.Primitive(face.part(),face.ordinal(),face.surface(),local)));
                meshes.add(new SceneCompiler().compile(input));
            }
        }
        meshes.add(original.geometry().get(1));var scene=new SceneStore.Revision(1,1,meshes,meshes.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
        var result=new SceneMediaCompiler().prepare(scene,domain());assertEquals(36,result.work().triangles());
        assertEquals(GlassFixtures.glass(),result.classify(InitialMediaFixtures.frame()).initialMedia().orElseThrow().enclosures());
    }
    private static SceneInputs.Corner mid(SceneInputs.Corner a,SceneInputs.Corner b) {
        return new SceneInputs.Corner(new SceneInputs.Vec3((a.position().x()+b.position().x())*.5f,(a.position().y()+b.position().y())*.5f,(a.position().z()+b.position().z())*.5f),
            (a.u()+b.u())*.5f,(a.v()+b.v())*.5f,a.tint(),a.normal(),a.tangent());
    }
    static RenderFrame at(SceneInputs.Origin eye) {
        var source=SceneFixtures.frame();return RenderFrame.fromCamera(16,16,0,eye,CameraFixtures.rows(new Matrix4f()),
            CameraFixtures.rows(new Matrix4f().perspective((float)Math.PI/2,1,.1f,100,true)),source.environment(),RenderFrame.DepthConvention.FORWARD);
    }
    private static SceneInputs.Transform transform(Matrix4f m) {
        return new SceneInputs.Transform(new float[]{m.m00(),m.m10(),m.m20(),m.m30(),m.m01(),m.m11(),m.m21(),m.m31(),m.m02(),m.m12(),m.m22(),m.m32()});
    }
    private static SceneInputs.Geometry copy(SceneInputs.Geometry g,SceneInputs.Origin origin,SceneInputs.Transform transform,List<SceneInputs.Primitive> primitives) {
        return new SceneInputs.Geometry(g.key(),g.revision(),origin,transform,transform,false,g.motion(),g.participation(),primitives);
    }
    private static SceneStore.Revision transformed(SceneInputs.Origin origin,SceneInputs.Transform pose) {
        var source=OrientedMediumFixtures.scene(false,0);var compiler=new SceneCompiler();var meshes=source.geometry().stream().map(g->compiler.compile(copy(g.input(),origin,pose,g.input().primitives()))).toList();
        return new SceneStore.Revision(1,1,meshes,meshes.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
}
