package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.*;
import org.lwjgl.vulkan.VK12;


public final class ProducerSceneGpuTest {
    private ProducerSceneGpuTest() {}
    public static void main(String[] arguments) throws Exception {
        var shaders=R2ShaderPackage.read(Path.of(arguments[0]));
        var capabilities=new HostExecution.Capabilities(1,Set.of(HostExecution.Capability.BUFFER_ADDRESS,
            HostExecution.Capability.ACCELERATION_STRUCTURE,HostExecution.Capability.RAY_QUERY),256,1_000_000);
        try(var host=new FixtureHost();var scenes=new ProducerGpuScene(host.resources,capabilities,shaders);
            var renderer=new R2Renderer(host.resources,capabilities,shaders);
            var vertices=host.resources.allocateMapped(112,VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            var original=SceneFixtures.prepare(false).geometry().getFirst().input();var primitive=original.primitives().getFirst();
            try(var mapping=vertices.map(0,112)) {
                var bytes=mapping.data().order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<4;i++) {
                    var corner=primitive.corners().get(i);int base=i*28;
                    bytes.putFloat(base,corner.position().x()).putFloat(base+4,corner.position().y()).putFloat(base+8,corner.position().z());
                    bytes.putInt(base+12,0xff010203).putFloat(base+16,corner.u()).putFloat(base+20,corner.v()).putInt(base+24,0);
                }
            }
            var grant=new HostExecution.BufferGrant(vertices.view(),1,Set.of(HostExecution.Access.TRANSFER_READ),host.resources.recording());
            var geometry=new ProducerResources.Geometry(original.key(),1,1,1,1,1,grant,ProducerResources.Layout.BLOCK28,
                ProducerResources.Topology.QUADS_012_230,4,original.origin(),original.current(),original.previous(),original.participation(),
                List.of(new ProducerResources.Primitive(primitive.part(),primitive.ordinal(),primitive.surface(),0xff804020)));
            var registry=new ProducerRegistry(1);registry.publish(geometry);
            var frame=SceneFixtures.frame().withReconstruction(RenderFrame.Reconstruction.RAW_DIAGNOSTIC).withWorldLightReuse(false);
            ByteBuffer reference=null;long rawHandle=0;
            for(int step=0;step<3;step++) {
                long serial=host.resources.recording();var command=host.begin();
                var window=new HostExecution.Window(1,serial,serial,HostExecution.Stage.SCENE_PREPARATION,step==0?List.of(grant):List.of());
                if(step==0)vertices.markUsed();
                if(step==2)registry.withdraw(new ProducerResources.Withdrawal(geometry.key(),1,1,1));
                try(var prepared=scenes.prepare(window,registry,frame.origin(),List.of(),LightInputs.Publication.empty(),null)) {
                    prepared.record(command);
                    if(prepared.builtMeshes()!=(step==0?1:0) || prepared.copiedSourceBytes()!=(step==0?112:0))
                        throw new AssertionError("Static producer input was copied or built again");
                    try(var output=renderer.record(new HostExecution.Window(1,serial,serial,HostExecution.Stage.WORLD,List.of()),command,prepared.scene(),frame,true);
                        var raw=host.copy(command,output.raw());var hits=host.copy(command,output.hits())) {
                        if(step==1 && output.raw().view().handle()!=rawHandle)throw new AssertionError("Completed output scratch was not reused");
                        rawHandle=output.raw().view().handle();
                        long submitted=host.submit(command);prepared.submitted(submitted);output.submitted(submitted);host.complete(command,submitted);
                        var pixels=host.read(raw);var hitBytes=host.read(hits);
                        int covered=0;for(int i=0;i<frame.width()*frame.height();i++)
                            if((hitBytes.getInt(i*dev.rt_render_experiment.engine.abi.R2Abi.HitRecord.SIZE+dev.rt_render_experiment.engine.abi.R2Abi.HitRecord.FLAGS)&1)!=0)covered++;
                        if((step<2 && covered==0)||(step==2 && covered!=0))throw new AssertionError("Publication/withdrawal did not change actual ray visibility");
                        for(int i=0;i<pixels.remaining()/4;i++)if(!Float.isFinite(pixels.getFloat(i*4)))throw new AssertionError("Non-finite producer radiance");
                        if(step==0)reference=pixels;
                        if(step==1 && reference.mismatch(pixels)!=-1)throw new AssertionError("Unchanged producer publication changed optical output");
                        System.out.println("Producer R2 scene PASS step="+step+" covered="+covered+" copies="+prepared.copiedSourceBytes()+" builds="+prepared.builtMeshes());
                    }
                }
            }
            host.assertValidation();
        }
    }
}
