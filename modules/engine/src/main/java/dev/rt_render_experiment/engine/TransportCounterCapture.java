package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.vulkan.VulkanBufferReadback;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.vulkan.VkCommandBuffer;


final class TransportCounterCapture implements AutoCloseable {
    private final VulkanResources resources;
    private final List<Capture> pending=new ArrayList<>();
    private boolean captured;
    TransportCounterCapture(VulkanResources resources) {this.resources=resources;}
    Capture record(VkCommandBuffer command,VulkanResources.Buffer counters,RenderFrame frame) {
        drain();
        if(captured || !Boolean.getBoolean("rt_render_experiment.counters") || frame.sample()<Integer.getInteger("rt_render_experiment.counterFrame",60)
            || frame.width()!=3840 || frame.height()!=2160 || frame.view()!=RenderFrame.View.WORLD)return null;
        captured=true;var capture=new Capture(counters,frame.width()*frame.height());
        capture.data.record(command);capture.data.acceptRecording();return capture;
    }
    final class Capture implements AutoCloseable {
        private final VulkanBufferReadback data;
        private final int pixels;
        private boolean submitted;
        Capture(VulkanResources.Buffer counters,int pixels) {this.pixels=pixels;data=new VulkanBufferReadback(resources,counters);}
        void submitted() {submitted=true;pending.add(this);}
        @Override public void close() {if(!submitted)data.close();}
    }
    private void drain() {
        for(var iterator=pending.iterator();iterator.hasNext();) {
            var capture=iterator.next();if(!capture.data.ready())continue;
            try(var readback=capture.data.readback()) {
                long[] sums=new long[8],maximum=new long[8];
                for(int p=0;p<capture.pixels;p++)for(int n=0;n<8;n++) {
                    long value=Integer.toUnsignedLong(readback.data().getInt(p*32+n*4));
                    sums[n]+=value;maximum[n]=Math.max(maximum[n],value);
                }
                System.out.println("RT_RENDER_EXPERIMENT_NATIVE_COUNTERS pixels="+capture.pixels+" totals="+java.util.Arrays.toString(sums)+" maximum="+java.util.Arrays.toString(maximum));
            }
            capture.data.close();iterator.remove();
        }
    }
    @Override public void close() {drain();for(var capture:pending)capture.data.close();pending.clear();}
}
