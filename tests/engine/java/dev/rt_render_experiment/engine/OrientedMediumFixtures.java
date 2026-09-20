package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.SceneInputs;
import org.lwjgl.vulkan.VK12;


public final class OrientedMediumFixtures {
    private OrientedMediumFixtures() {}
    private static final long OUTER=0x100000001L,INNER=0x200000001L;
    private static final float[] INNER_TINT={.3f,.6f,.9f};
    static SceneStore.Revision scene(boolean negative,int defect) {
        var meshes=new ArrayList<SceneCompiler.Compiled>();
        for(int object=0;object<3;object++) {
            var key=new SceneInputs.Key(995,object+1);var neutral=new SceneInputs.Key(0,0);
            var layers=object<2 || defect==3?MaterialInputs.Layers.plain(neutral):new MaterialInputs.Layers(neutral,true,neutral,MaterialInputs.Emission.CALIBRATED,
                new SceneInputs.Vec3(5,3,1),true,false,MaterialInputs.Sampling.CRISP);
            var surface=new SceneInputs.Surface(key,object==0?24:object==1?43:defect==3?37:1,0,neutral,neutral,
                object<2?SceneInputs.Coverage.DIELECTRIC:SceneInputs.Coverage.OPAQUE,.5f,true,0,0,0,layers,
                object<2?SceneInputs.HostOcclusion.PASS:SceneInputs.HostOcclusion.BLOCK);
            if(object<2 && !(object==1 && defect==2))surface=surface.withNestedVolume(object==0?OUTER:INNER);
            var tint=object==0?new SceneInputs.Color(.8f,.8f,.8f,1):object==1?new SceneInputs.Color(INNER_TINT[0],INNER_TINT[1],INNER_TINT[2],1):new SceneInputs.Color(1,1,1,1);
            var primitives=new ArrayList<SceneInputs.Primitive>();float extent=object==1?16:32,front=-1-object,back=object==0?-4:object==1?-3:-5;
            if(object==2) { front=defect==3?-2.5f:-5;back=front; }
            for(int face=0;face<(object<2?6:1);face++) {
                int axis=face/2;boolean positive=(face&1)==0;
                if(object==2)axis=2;
                int u=(axis+1)%3,v=(axis+2)%3;if(!positive) { int swap=u;u=v;v=swap; }
                var corners=new ArrayList<SceneInputs.Corner>();
                for(int[] sign:new int[][]{{-1,-1},{1,-1},{1,1},{-1,1}}) {
                    float[] p={0,0,(front+back)*.5f},n={0,0,0},t={0,0,0};
                    p[axis]=axis==2?(positive?front:back):(positive?extent:-extent);
                    p[u]+=sign[0]*(u==2?(front-back)*.5f:extent);p[v]+=sign[1]*(v==2?(front-back)*.5f:extent);
                    n[axis]=positive?1:-1;t[u]=1;
                    corners.add(new SceneInputs.Corner(new SceneInputs.Vec3(p[0],p[1],p[2]),0,0,tint,new SceneInputs.Vec3(n[0],n[1],n[2]),new SceneInputs.Vec3(t[0],t[1],t[2])));
                }
                var boundary=object==1 && defect==1 && face==5?surface.withNestedVolume(0x300000001L):surface;
                primitives.add(new SceneInputs.Primitive(key,face,boundary,corners));
            }
            float[] pose=SceneInputs.Transform.identity().rows();if(negative)pose[0]=-1;
            meshes.add(new SceneCompiler().compile(new SceneInputs.Geometry(key,new SceneInputs.Revision(1,1,0,negative?2:1,defect+1,1,1),
                SceneFixtures.frame().origin(),new SceneInputs.Transform(pose),new SceneInputs.Transform(pose),true,SceneInputs.Motion.STATIC,
                new Participation(Participation.WORLD,true),primitives)));
        }
        return new SceneStore.Revision(1,1,meshes,meshes.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
    private static void save(Path file,ByteBuffer data) throws Exception { var bytes=new byte[data.remaining()];data.duplicate().get(bytes);Files.write(file,bytes,StandardOpenOption.CREATE_NEW); }
    private static HostExecution.ImageGrant grant(ResourceViews.Image image,long serial) { return new HostExecution.ImageGrant(image,1,Set.of(HostExecution.Access.ATTACHMENT_WRITE),serial); }
    private static HostExecution.Window window(long serial,long transaction,HostExecution.Stage stage,HostExecution.ImageGrant... images) { return new HostExecution.Window(1,transaction,serial,stage,List.of(),List.of(images)); }}
