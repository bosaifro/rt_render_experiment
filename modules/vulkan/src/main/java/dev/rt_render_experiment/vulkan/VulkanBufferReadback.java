package dev.rt_render_experiment.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;


public final class VulkanBufferReadback implements AutoCloseable {
    private final VulkanResources resources;
    private final VulkanResources.Buffer source,target;
    private long recording;
    private boolean accepted;
    public VulkanBufferReadback(VulkanResources resources,VulkanResources.Buffer source) {
        var view=source.view();
        if(view.device()!=resources.deviceIdentity() || (view.usage()&VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)==0)
            throw new IllegalArgumentException("Readback requires an owned transfer-readable buffer on this device");
        this.resources=resources;this.source=source;target=resources.allocateReadback(Math.toIntExact(view.length()));
    }
    public void record(VkCommandBuffer command) {
        if(recording!=0 || command.getDevice().address()!=resources.device().address())throw new IllegalStateException("Repeated or foreign readback recording");
        source.markUsed();target.markUsed();recording=resources.recording();
        try(var stack=MemoryStack.stackPush()) {
            VulkanBarriers.record(command,stack,VulkanBarriers.ARBITRARY_WRITES_TO_CAPTURE);
            VK12.vkCmdCopyBuffer(command,source.view().handle(),target.view().handle(),VkBufferCopy.calloc(1,stack)
                .srcOffset(source.view().offset()).size(source.view().length()));


            VulkanBarriers.record(command,stack,VulkanBarriers.CAPTURE_TO_CONSUMERS);
        }
    }
    public void acceptRecording() {
        if(recording==0 || recording!=resources.recording() || accepted || target.closed())throw new IllegalStateException("Readback recording is not acceptable");
        accepted=true;
    }
    public boolean ready() { return accepted && !target.closed() && recording<=resources.completed(); }
    public VulkanResources.Buffer.Mapping readback() {
        if(!ready())throw new IllegalStateException("Buffer readback has not completed an accepted recording");
        return target.map(0,target.view().length());
    }
    @Override public void close() { if(!target.closed())target.close(); }
}
