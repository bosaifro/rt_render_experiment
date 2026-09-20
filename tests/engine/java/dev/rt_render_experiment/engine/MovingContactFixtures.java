package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import dev.rt_render_experiment.contract.*;
import org.lwjgl.vulkan.VK12;


public final class MovingContactFixtures {
    private MovingContactFixtures() {}
    private static final MediumInputs.Definition WATER=MediumProfiles.definition(MediumInputs.Kind.WATER,new SceneInputs.Vec3(1,1,1));
    static FrameScene content(long serial,SceneStore.Revision resident,DynamicSceneCompiler.Prepared dynamic,boolean automatic,boolean refused) {
        var input=new FrameScene(serial,resident,dynamic,List.of(),LightInputs.Publication.empty());
        if(automatic)return input.withPreparedVolumes();if(refused)return input;


        var water=resident.geometry().getFirst();var top=water.parts().stream().filter(p->p.ordinal()%6==1).findFirst().orElseThrow();
        var positions=water.positions();var points=new ArrayList<SceneInputs.Vec3>();
        points.add(new SceneInputs.Vec3(1,0,-2));points.add(new SceneInputs.Vec3(1,0,-1));
        for(int corner:new int[]{2,3}) { int at=(top.firstVertex()+corner)*SceneCompiler.POSITION_STRIDE;points.add(new SceneInputs.Vec3(positions.getFloat(at),positions.getFloat(at+4),positions.getFloat(at+8))); }
        var moving=dynamic.geometry().getFirst();var supports=moving.parts().stream().map(p->new ClipGeometry.Support(moving,p)).toList();
        var local=new ArrayList<SceneInputs.Vec3>();boolean exact=true;
        for(var p:points) {
            var q=ConvexVolume.point(moving.input(),water.input(),p.x(),p.y(),p.z());
            exact&=q.x()==(float)q.x() && q.y()==(float)q.y() && q.z()==(float)q.z();
            local.add(new SceneInputs.Vec3((float)q.x(),(float)q.y(),(float)q.z()));
        }
        var anchor=exact?moving:water;var corners=exact?local:points;
        var mappings=new HashMap<SceneCompiler.Compiled,SceneCompiler.Compiled>();var compiler=new SceneCompiler();
        for(var mesh:input.revision().geometry()) {
            var volumes=new HashMap<SceneCompiler.Part,Long>();var contacts=new HashMap<SceneCompiler.Part,Long>();
            for(var part:mesh.parts())if(part.surface().material()==8)volumes.put(part,1L);else if(mesh.input().key().high()==5600)contacts.put(part,1L);
            var prepared=mesh==anchor || !volumes.isEmpty() || !contacts.isEmpty()?compiler.withVolumes(mesh,volumes,contacts):mesh;
            if(mesh==anchor)prepared=compiler.withClipContacts(prepared,List.of(new ClipGeometry.Contact(moving.parts().getFirst(),1,corners,supports)));
            if(prepared!=mesh)mappings.put(mesh,prepared);
        }
        return input.withCompiledVolumes(mappings,new MediumInputs.Domain(List.of(new MediumInputs.Volume(1,WATER))));
    }
    private static RenderFrame camera(SceneInputs.Origin origin,int step) {
        float x=step==6 || step==7?.999f:.5f,y=step==7?.9f:.35f;
        var view=new org.joml.Matrix4f().rotationY((float)Math.PI/2).translate(-x,-y,1.5f);var env=SceneFixtures.frame().environment();
        env=new RenderFrame.Environment(env.sun(),env.moon(),env.dayFraction(),0,0,false,-100000,env.sceneRadius(),false,env.cloud());
        return RenderFrame.fromCamera(16,16,0,origin,CameraFixtures.rows(view),CameraFixtures.rows(new org.joml.Matrix4f().perspective((float)Math.PI/6,1,.1f,100,true)),env,RenderFrame.DepthConvention.FORWARD)
            .withReconstruction(RenderFrame.Reconstruction.PORTABLE);
    }
    private static Path directory(Path parent,String name) throws Exception { if(parent==null)return null;var path=parent.resolve(name);Files.createDirectory(path);return path; }
    private static HostExecution.ImageGrant grant(ResourceViews.Image image,long serial) { return new HostExecution.ImageGrant(image,1,Set.of(HostExecution.Access.ATTACHMENT_WRITE),serial); }
    private static HostExecution.Window window(long serial,long id,HostExecution.Stage stage,HostExecution.ImageGrant... images) { return new HostExecution.Window(1,id,serial,stage,List.of(),List.of(images)); }}
