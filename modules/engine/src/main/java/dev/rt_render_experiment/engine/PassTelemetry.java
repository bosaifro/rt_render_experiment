package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import dev.rt_render_experiment.vulkan.VulkanDiagnostics;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


final class PassTelemetry implements AutoCloseable {
    private static final boolean ENABLED=Boolean.getBoolean("rt_render_experiment.profileDispatches");
    private static final IdentityHashMap<VulkanResources,PassTelemetry> CONTEXTS=new IdentityHashMap<>();
    private record Pass(String name,int x,int y,boolean compute) {}
    private static final class Batch {
        final long recording;final VulkanDiagnostics.Slot slot;final List<Pass> passes=new ArrayList<>();
        Batch(long recording,VulkanDiagnostics.Slot slot) {this.recording=recording;this.slot=slot;}
    }
    private final VulkanResources resources;
    private final VulkanDiagnostics diagnostics;
    private final double period;
    private final List<Batch> batches=new ArrayList<>();
    private Batch current;
    private PassTelemetry(VulkanResources resources) {
        this.resources=resources;diagnostics=new VulkanDiagnostics(resources,4,4,4096);
        try(var stack=MemoryStack.stackPush()) {
            var properties=VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(resources.device().getPhysicalDevice(),properties);period=properties.limits().timestampPeriod();
        }
    }
    static void begin(VulkanResources resources,VkCommandBuffer command,String entry,int x,int y,boolean compute) {
        if(!ENABLED)return;
        CONTEXTS.computeIfAbsent(resources,PassTelemetry::new).start(command,entry,x,y,compute);
    }
    static void end(VulkanResources resources,VkCommandBuffer command) {
        if(!ENABLED)return;var context=CONTEXTS.get(resources);
        if(context.current.slot.queryPool()!=0)VK12.vkCmdWriteTimestamp(command,VK12.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
            context.current.slot.queryPool(),context.current.passes.size()*2-1);
    }
    private void start(VkCommandBuffer command,String entry,int x,int y,boolean compute) {
        if(current==null||current.recording!=resources.recording()) {
            drain();current=new Batch(resources.recording(),diagnostics.acquire());batches.add(current);
            if(current.slot.queryPool()!=0)VK12.vkCmdResetQueryPool(command,current.slot.queryPool(),0,4096);
        }
        if(current.passes.size()>=2048)throw new IllegalStateException("Pass telemetry capacity exceeded");
        if(current.slot.queryPool()!=0)VK12.vkCmdWriteTimestamp(command,VK12.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,current.slot.queryPool(),current.passes.size()*2);
        current.passes.add(new Pass(entry,x,y,compute));
    }
    private void drain() {
        for(var iterator=batches.iterator();iterator.hasNext();) {
            Batch batch=iterator.next();if(!batch.slot.completed())continue;
            if(batch.slot.queryPool()!=0 && !batch.passes.isEmpty())try(var stack=MemoryStack.stackPush()) {
                int count=batch.passes.size()*2;var values=stack.mallocLong(count*2);
                int result=VK12.vkGetQueryPoolResults(resources.device(),batch.slot.queryPool(),0,count,values,16,
                    VK12.VK_QUERY_RESULT_64_BIT|VK12.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if(result==VK12.VK_SUCCESS || result==VK12.VK_NOT_READY)for(int i=0;i<batch.passes.size();i++) {
                    if(values.get(i*4+1)==0 || values.get(i*4+3)==0) { System.out.println("RT_RENDER_EXPERIMENT_LIVE_UNAVAILABLE recording="+batch.recording+" entry="+batch.passes.get(i).name+" completed="+resources.completed()); continue; }
                    var pass=batch.passes.get(i);double ms=(values.get(i*4+2)-values.get(i*4))*period/1e6;
                    System.out.printf(Locale.ROOT,"RT_RENDER_EXPERIMENT_LIVE_PASS recording=%d ordinal=%d entry=%s x=%d y=%d compute=%s gpuMs=%.9f%n",
                        batch.recording,i,pass.name,pass.x,pass.y,pass.compute,ms);
                }
            }
            batch.slot.release();iterator.remove();
        }
    }
    static void release(VulkanResources resources) {var context=CONTEXTS.remove(resources);if(context!=null)context.close();}
    @Override public void close() {drain();for(var batch:batches)batch.slot.release();batches.clear();diagnostics.close();}
}
