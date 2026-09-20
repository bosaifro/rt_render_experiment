package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.DynamicInputs;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.TextureInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FrameSceneTest {
    @Test void completeStaticDynamicAndSourceMembership() {
        var resident=SceneFixtures.prepare(false);var dynamic=dynamic(new SceneInputs.Key(-1,9),new SceneInputs.Key(0,0));
        var item=dynamic.geometry().getFirst();var part=item.parts().getFirst();
        var area=new LightInputs.Area(new SceneInputs.Key(8,1),1,new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0)),
            item.input().origin(),0,LightInputs.Stratum.OBJECT,item.input().key(),part.key(),part.ordinal());
        var lights=new LightInputs.Publication(1,List.of(area,point(2),point(3)));
        var frame=new FrameScene(19,resident,dynamic,List.of(),lights);
        assertEquals(19,frame.revision().serial());assertEquals(2,frame.revision().geometry().size());
        assertSame(item,frame.revision().geometry().getLast());assertSame(resident.geometry().getFirst(),frame.revision().geometry().getFirst());
        assertSame(dynamic,frame.dynamic());assertEquals(3,frame.revision().lights().size());
        assertEquals(resident.bytes()+dynamic.revision(1).bytes(),frame.revision().bytes());
        var compiled=new LightCompiler().compile(frame.revision().geometry(),frame.revision().lights(),item.input().origin());
        assertEquals(2,compiled.attachedCount());assertEquals(3,compiled.dynamicCount());assertEquals(1,compiled.associations().size());

        dynamic.submitted();
    }
    @Test void resourceAndAssociationAdmissionIsAtomic() {
        var key=new SceneInputs.Key(21,1);var resident=SceneFixtures.prepare(false);
        var dynamic=dynamic(new SceneInputs.Key(-1,9),key);
        assertThrows(IllegalArgumentException.class,()->new FrameScene(2,resident,dynamic,List.of(),LightInputs.Publication.empty()));
        var red=texture(key,1,255);var copy=texture(key,1,255);
        var valid=new FrameScene(2,resident,dynamic,List.of(red,copy),LightInputs.Publication.empty());
        assertEquals(1,valid.revision().textures().size());assertSame(red,valid.revision().textures().getFirst());
        assertThrows(IllegalArgumentException.class,()->new FrameScene(2,resident,dynamic,List.of(red,texture(key,1,32)),LightInputs.Publication.empty()));
        assertThrows(IllegalArgumentException.class,()->new FrameScene(2,resident,dynamic,List.of(red,texture(key,2,255)),LightInputs.Publication.empty()));
        var missing=new LightInputs.Area(new SceneInputs.Key(5,5),1,new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0)),
            resident.geometry().getFirst().input().origin(),0,LightInputs.Stratum.OBJECT,new SceneInputs.Key(99,99),new SceneInputs.Key(1,1),0);
        assertThrows(IllegalArgumentException.class,()->new FrameScene(2,resident,dynamic,List.of(red),new LightInputs.Publication(1,List.of(missing))));
        assertThrows(IllegalArgumentException.class,()->new FrameScene(2,resident,dynamic,List.of(red),new LightInputs.Publication(1,List.of(point(1),point(1)))));
        assertSame(resident.geometry().getFirst(),valid.revision().geometry().getFirst());
        dynamic.submitted();
    }
    @Test void duplicatesAndEmptyWorldsCannotHideBehindNoTriangles() {
        var resident=SceneFixtures.prepare(false);
        var collision=dynamic(resident.geometry().getFirst().input().key(),new SceneInputs.Key(0,0));
        assertThrows(IllegalArgumentException.class,()->new FrameScene(1,resident,collision,List.of(),LightInputs.Publication.empty()));
        var emptyOtherWorld=new DynamicSceneCompiler().prepare(2,1,List.of(),4096);
        assertThrows(IllegalArgumentException.class,()->new FrameScene(1,resident,emptyOtherWorld,List.of(),LightInputs.Publication.empty()));
        var empty=new DynamicSceneCompiler().prepare(1,1,List.of(),4096);
        assertEquals(resident.geometry(),new FrameScene(1,resident,empty,List.of(),LightInputs.Publication.empty()).revision().geometry());
        var part=resident.geometry().getFirst().parts().getFirst();
        var area=new LightInputs.Area(new SceneInputs.Key(8,1),1,new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0)),
            new SceneInputs.Origin(0,64,0),0,LightInputs.Stratum.WORLD,resident.geometry().getFirst().input().key(),part.key(),part.ordinal());
        var duplicate=new LightInputs.Area(new SceneInputs.Key(8,2),1,area.emission(),area.samplingOrigin(),0,area.stratum(),area.geometry(),area.part(),area.ordinal());
        assertThrows(IllegalArgumentException.class,()->new FrameScene(1,resident,empty,List.of(),new LightInputs.Publication(1,List.of(area,duplicate))));
    }
    @Test void frameOnlyGrantsAndCloudFactsAreCompleteBeforeRecording() {
        var key=new SceneInputs.Key(51,2);
        var view=new ResourceViews.Image(new ResourceViews.ResourceId(1),1,2,3,1,4,37,4,2,0,1,0,1,1,4,5);
        var grant=new HostExecution.ImageGrant(view,4,Set.of(HostExecution.Access.SHADER_READ),10);
        var texture=new TextureInputs.GpuTexture(key,4,TextureInputs.Encoding.SRGB,TextureInputs.Address.REPEAT,grant);
        var scene=new FrameScene(1,SceneFixtures.prepare(false),null,List.of(texture),LightInputs.Publication.empty());
        assertEquals(List.of(texture),scene.imports());assertTrue(scene.revision().textures().isEmpty(),"Foreign grants entered the persistent source store");
        var empty=new HostExecution.Window(1,1,10,HostExecution.Stage.SCENE_PREPARATION,List.of());
        assertThrows(IllegalArgumentException.class,()->scene.requireReadGrants(empty));
        scene.requireReadGrants(new HostExecution.Window(1,1,10,HostExecution.Stage.SCENE_PREPARATION,List.of(),List.of(grant)));
        var e=SceneFixtures.frame().environment();
        scene.requireEnvironment(new RenderFrame.Environment(e.sun(),e.moon(),e.dayFraction(),e.rain(),0,true,64,128,false,new RenderFrame.Cloud(key,4,2,192,4,12)));
        assertThrows(IllegalArgumentException.class,()->scene.requireEnvironment(new RenderFrame.Environment(e.sun(),e.moon(),e.dayFraction(),e.rain(),0,true,64,128,false,new RenderFrame.Cloud(key,8,2,192,4,12))));
        assertThrows(IllegalArgumentException.class,()->scene.requireEnvironment(new RenderFrame.Environment(e.sun(),e.moon(),e.dayFraction(),e.rain(),0,true,64,128,false)));
    }
    static DynamicSceneCompiler.Prepared dynamic(SceneInputs.Key identity,SceneInputs.Key resource) {
        var input=SceneFixtures.prepare(false,SceneInputs.Coverage.OPAQUE,resource).geometry().getFirst().input();
        var source=new DynamicInputs.PreparedObject(identity,input.origin(),input.current(),SceneInputs.Motion.RIGID,input.participation(),1,1,input.primitives());
        return new DynamicSceneCompiler().prepare(1,1,List.of(source),8192);
    }
    private static LightInputs.Point point(long hand) {
        var position=new SceneInputs.Origin(0,64,0);
        return new LightInputs.Point(new SceneInputs.Key(9,hand),1,new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0)),position,0,LightInputs.Stratum.CAMERA_ATTACHED,position,0.12f);
    }
    private static TextureInputs.Texture texture(SceneInputs.Key key,long revision,int red) {
        return new TextureInputs.Texture(key,revision,TextureInputs.Encoding.SRGB,TextureInputs.Address.CLAMP,
            List.of(new TextureInputs.Level(1,1,ByteBuffer.wrap(new byte[]{(byte)red,0,0,-1}))));
    }
}
