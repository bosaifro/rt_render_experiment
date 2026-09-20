package dev.rt_render_experiment.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.ResourceViews;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VK12;


public final class InitialMediaFixtures {
    private InitialMediaFixtures() {}
    static RenderFrame frame() {
        var base=SceneFixtures.frame();var environment=base.environment();
        var isolated=new RenderFrame.Environment(environment.sun(),environment.moon(),environment.dayFraction(),0,0,false,-100000,128,false);
        return RenderFrame.fromCamera(16,16,0,base.origin(),CameraFixtures.rows(new Matrix4f().translation(0,0,2.5f)),
            CameraFixtures.rows(new Matrix4f().perspective((float)Math.PI/2,1,.1f,100,true)),isolated,RenderFrame.DepthConvention.FORWARD);
    }
    private record Result(byte[] hits,byte[] signals,byte[] radiance,byte[] history,byte[] display) {}
    private static FrameScene content(long serial,SceneStore.Revision scene,boolean published,dev.rt_render_experiment.contract.MediumInputs.Domain domain) {
        var result=new FrameScene(serial,scene,null,List.of(),LightInputs.Publication.empty());return published?result.withMediumDomain(domain):result;
    }
    private static SceneStore.Revision waterScene() {
        var original=OrientedMediumFixtures.scene(false,0);var meshes=new java.util.ArrayList<SceneCompiler.Compiled>();
        for(int object:new int[]{0,2}) {
            var input=original.geometry().get(object).input();
            if(object==2) { meshes.add(original.geometry().get(object));continue; }
            var primitives=input.primitives().stream().map(p->{
                var s=p.surface();var surface=new dev.rt_render_experiment.contract.SceneInputs.Surface(s.key(),8,0,s.colorResource(),s.emissionResource(),s.coverage(),s.cutoff(),true,
                    s.medium(),0,0,s.layers(),s.hostOcclusion(),s.boundary());
                return new dev.rt_render_experiment.contract.SceneInputs.Primitive(p.part(),p.ordinal(),surface,p.corners());
            }).toList();
            meshes.add(new SceneCompiler().compile(new dev.rt_render_experiment.contract.SceneInputs.Geometry(input.key(),input.revision(),input.origin(),input.current(),input.previous(),false,
                dev.rt_render_experiment.contract.SceneInputs.Motion.RIGID,input.participation(),primitives)));
        }
        return new SceneStore.Revision(1,1,meshes,meshes.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
    private static HostExecution.ImageGrant grant(ResourceViews.Image image,long serial) { return new HostExecution.ImageGrant(image,1,Set.of(HostExecution.Access.ATTACHMENT_WRITE),serial); }
    private static HostExecution.Window window(long serial,long transaction,HostExecution.Stage stage,HostExecution.ImageGrant... images) { return new HostExecution.Window(1,transaction,serial,stage,List.of(),List.of(images)); }}
