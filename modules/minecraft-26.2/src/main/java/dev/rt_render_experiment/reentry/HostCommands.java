package dev.rt_render_experiment.reentry;

import java.util.function.Consumer;
import dev.rt_render_experiment.reentry.mixin.VulkanEncoderAccessor;
import dev.rt_render_experiment.vulkan.VulkanObjects;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;


public final class HostCommands {
    private HostCommands() {}
    public static final class RecordingFailure extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final boolean handoffStarted;
        private RecordingFailure(Throwable cause,boolean handoffStarted) { super(cause);this.handoffStarted=handoffStarted; }
        public boolean canUseHostFallback() { return !handoffStarted; }
    }
    public static void record(HostDevice host,Consumer<VkCommandBuffer> record) {
        var encoder=host.device.createCommandEncoder();
        if(((VulkanEncoderAccessor)encoder).rt_render_experiment$renderPass()!=null)throw new IllegalStateException("RtRenderExperiment entry is inside a host render pass");
        boolean handoff=false;
        try {
            var command=encoder.allocateAndBeginTransientCommandBuffer();
            record.accept(command);
            VulkanObjects.requireSuccess(VK12.vkEndCommandBuffer(command),"End producer renderer recording");

            handoff=true;encoder.execute(command);
        } catch(RuntimeException|Error failure) { throw new RecordingFailure(failure,handoff); }
    }
}
