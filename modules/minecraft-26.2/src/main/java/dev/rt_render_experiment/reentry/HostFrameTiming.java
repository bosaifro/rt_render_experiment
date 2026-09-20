package dev.rt_render_experiment.reentry;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TimerQuery;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.rt_render_experiment.reentry.mixin.GpuDeviceAccessor;
import dev.rt_render_experiment.reentry.mixin.TimerQueryAccessor;
import net.minecraft.client.Minecraft;


public final class HostFrameTiming {
    private static TimerQuery query;
    private static boolean open,awaiting,invalid;
    private static int submissions,width,height;
    private static long serial,producerBefore,cpuStart,cpuNs;
    private static long invocation,startedInvocation;
    private HostFrameTiming() {}
    public static void begin() {
        if(!Boolean.getBoolean("rt_render_experiment.timings") || !VulkanAdmission.captureEnabled())return;
        invocation++;
        if(query==null) {
            var device=(VulkanDevice)((GpuDeviceAccessor)RenderSystem.getDevice()).rt_render_experiment$backend();
            query=new TimerQuery();var owned=query;HostDevice.of(device).own(()->{owned.close();if(query==owned)query=null;});
        }
        if(awaiting && query.getStatus()==TimerQuery.Status.NOT_RECORDING) {
            var access=(TimerQueryAccessor)query;long nanos=access.rt_render_experiment$results()[access.rt_render_experiment$rotation()];
            if(!invalid && submissions==1 && nanos>0)RtRenderExperimentClient.LOGGER.info("RT_RENDER_EXPERIMENT_HOST_FRAME recording={} extent={}x{} gpuNs={} cpuRenderNs={} qualification=completed-single-submission",serial,width,height,nanos,cpuNs);
            awaiting=false;
        }
        if(open)invalid=true;
        if(!open && !awaiting) {
            query.beginProfile();open=true;invalid=false;submissions=0;
            startedInvocation=invocation;
            var device=(VulkanDevice)((GpuDeviceAccessor)RenderSystem.getDevice()).rt_render_experiment$backend();serial=HostDevice.of(device).recording();
            var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();width=target.width;height=target.height;
            producerBefore=RtRenderExperimentFrameGraph.producerFrames();cpuStart=System.nanoTime();
        }
    }
    public static void beforeSubmit() {
        if(query==null || !Boolean.getBoolean("rt_render_experiment.timings"))return;
        if(open) {
            query.endProfile();open=false;awaiting=true;cpuNs=System.nanoTime()-cpuStart;
            invalid|=RtRenderExperimentFrameGraph.producerFrames()!=producerBefore+1;
        }
        if(awaiting && invocation==startedInvocation)submissions++;
    }
}
