package dev.rt_render_experiment.engine;

import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;


final class ComputeDispatch {
    private ComputeDispatch() {}
    interface Observer {
        void before(VkCommandBuffer command, String entry, int x, int y);
        void after(VkCommandBuffer command);
    }
    private static final ThreadLocal<Observer> OBSERVER = new ThreadLocal<>();
    static void observe(Observer observer) {
        if (observer == null) OBSERVER.remove(); else OBSERVER.set(observer);
    }

    static void recordIndirect(dev.rt_render_experiment.vulkan.VulkanResources resources,VkCommandBuffer command,String entry,long arguments,long offset) {
        PassTelemetry.begin(resources,command,entry,-1,-1,true);Observer observer=OBSERVER.get();
        if(observer!=null)observer.before(command,entry,-1,-1);
        VK12.vkCmdDispatchIndirect(command,arguments,offset);
        if(observer!=null)observer.after(command);PassTelemetry.end(resources,command);
    }
    static void record(dev.rt_render_experiment.vulkan.VulkanResources resources, VkCommandBuffer command, String entry, int x, int y) {
        PassTelemetry.begin(resources,command,entry,x,y,true);
        Observer observer = OBSERVER.get();
        if (observer != null) observer.before(command, entry, x, y);
        VK12.vkCmdDispatch(command, x, y, 1);
        if (observer != null) observer.after(command);
        PassTelemetry.end(resources,command);
    }
}
