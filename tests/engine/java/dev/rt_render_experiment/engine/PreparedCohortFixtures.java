package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.SceneInputs;
import org.lwjgl.vulkan.VK12;


public final class PreparedCohortFixtures {
    private PreparedCohortFixtures() {}
    private static List<SceneInputs.PreparedSource> cohort(SceneStore store,int revision,float edge,boolean invalid) {
        var result=new java.util.ArrayList<SceneInputs.PreparedSource>();
        for(int side=0;side<2;side++) {
            var key=new SceneInputs.Key(2111,side+1);
            var version=new SceneInputs.Revision(1,revision,0,0,1,1,1);var request=store.request(key,version,4096);
            result.add(packet(request,side,edge,invalid));
        }
        return List.copyOf(result);
    }
    static SceneInputs.PreparedSource packet(SceneInputs.PreparationRequest request,int side,float edge,boolean invalid) {
        var key=request.source();var version=request.expected();var neutral=new SceneInputs.Key(0,0);
        var emission=side==0?new SceneInputs.Vec3(5,0,0):new SceneInputs.Vec3(0,0,5);
        var layers=new MaterialInputs.Layers(neutral,true,neutral,MaterialInputs.Emission.CALIBRATED,emission,true,false,MaterialInputs.Sampling.CRISP);
        var surface=new SceneInputs.Surface(key,1,0,invalid && side==1?new SceneInputs.Key(99,99):neutral,neutral,
            SceneInputs.Coverage.OPAQUE,.5f,true,0,0,0,layers,SceneInputs.HostOcclusion.BLOCK);
        float left=side==0?-1:edge,right=side==0?edge:1;
        var corners=new java.util.ArrayList<SceneInputs.Corner>();
        for(float[] p:new float[][]{{left,-1},{right,-1},{right,1},{left,1}})corners.add(new SceneInputs.Corner(new SceneInputs.Vec3(p[0],p[1],-3),0,0,
            new SceneInputs.Color(1,1,1,1),new SceneInputs.Vec3(0,0,1),new SceneInputs.Vec3(1,0,0)));
        var geometry=new SceneInputs.Geometry(key,version,new SceneInputs.Origin(0,64,0),SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),false,
            SceneInputs.Motion.STATIC,new Participation(Participation.WORLD,true),List.of(new SceneInputs.Primitive(key,0,surface,corners)));
        return SceneInputs.PreparedSource.geometryOnly(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,List.of(geometry),SceneCompiler.primitiveBytes(4),"Complete shared-edge source"));
    }
    private static void save(Path file,ByteBuffer data) throws Exception { byte[] b=new byte[data.remaining()];data.duplicate().get(b);Files.write(file,b,StandardOpenOption.CREATE_NEW); }
    private static HostExecution.ImageGrant grant(ResourceViews.Image image,long serial) { return new HostExecution.ImageGrant(image,1,Set.of(HostExecution.Access.ATTACHMENT_WRITE),serial); }
    private static HostExecution.Window window(long serial,long id,HostExecution.Stage stage,HostExecution.ImageGrant... images) { return new HostExecution.Window(1,id,serial,stage,List.of(),List.of(images)); }}
