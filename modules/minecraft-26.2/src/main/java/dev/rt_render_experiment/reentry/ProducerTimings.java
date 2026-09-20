package dev.rt_render_experiment.reentry;

import java.util.ArrayList;
import java.util.Locale;
import dev.rt_render_experiment.vulkan.VulkanDiagnostics;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;


final class ProducerTimings implements AutoCloseable {
    private final VulkanDiagnostics diagnostics;
    private final double period;
    private final ArrayList<Frame> pending=new ArrayList<>();
    ProducerTimings(VulkanResources resources,double period) {
        this.period=period;diagnostics=new VulkanDiagnostics(resources,8,4,6);
    }
    Frame begin(long id,int width,int height) {
        poll();
        if(!diagnostics.timestampsAvailable() || pending.size()>=16)return null;
        var frame=new Frame(id,width,height,diagnostics.acquire());pending.add(frame);return frame;
    }
    final class Frame {
        private final long id;
        private final int width,height;
        private final VulkanDiagnostics.Slot slot;
        private boolean complete;
        private int next;
        private Frame(long id,int width,int height,VulkanDiagnostics.Slot slot) { this.id=id;this.width=width;this.height=height;this.slot=slot; }
        void stamp(VkCommandBuffer command,int stage) {
            if(stage!=next || stage>=6)throw new IllegalStateException("GPU timing bracket out of order");
            if(stage==0)VK12.vkCmdResetQueryPool(command,slot.queryPool(),0,6);
            VK12.vkCmdWriteTimestamp(command,VK12.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,slot.queryPool(),stage);next++;
        }
        void complete() { complete=next==6; }
        void cancel() { pending.remove(this);slot.release(); }
    }
    void poll() {
        try(var stack=MemoryStack.stackPush()) {
            var values=stack.mallocLong(12);var iterator=pending.iterator();
            while(iterator.hasNext()) {
                var frame=iterator.next();if(!frame.complete || !frame.slot.completed())continue;
                values.clear();int status=frame.slot.timestamps(values);
                if(status==VK12.VK_NOT_READY)continue;
                if(status==VK12.VK_SUCCESS) {
                    double[] ms=new double[5];boolean valid=true;
                    for(int i=0;i<6;i++)valid&=values.get(i*2+1)!=0;
                    for(int i=0;i<5;i++) { ms[i]=(values.get((i+1)*2)-values.get(i*2))*period/1_000_000.0;valid&=Double.isFinite(ms[i]) && ms[i]>=0; }
                    if(valid)com.mojang.logging.LogUtils.getLogger().info(String.format(Locale.ROOT,
                        "RT_RENDER_EXPERIMENT_PRODUCER_GPU frame=%d extent=%dx%d rtUpdatesMs=%.6f opticalMs=%.6f handoffMs=%.6f hostWorldMs=%.6f displayMs=%.6f qualification=completed-timestamps",
                        frame.id,frame.width,frame.height,ms[0],ms[1],ms[2],ms[3],ms[4]));
                }
                frame.slot.release();iterator.remove();
            }
        }
    }
    @Override public void close() { poll();for(var frame:pending)frame.slot.release();pending.clear();diagnostics.close(); }
}
