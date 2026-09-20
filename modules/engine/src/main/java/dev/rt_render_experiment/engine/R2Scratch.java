package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.vulkan.VulkanResources;
import dev.rt_render_experiment.vulkan.VulkanRetirement;


final class R2Scratch implements AutoCloseable {
    private record BufferSlot(VulkanResources.Buffer value, long bytes, int usage, boolean mapped) {}
    private record ImageSlot(VulkanResources.Image value, int format, int usage, int width, int height, int aspect) {}
    private final VulkanResources resources;
    private final List<BufferSlot> buffers = new ArrayList<>();
    private final List<ImageSlot> images = new ArrayList<>();

    R2Scratch(VulkanResources resources) { this.resources = resources; }

    VulkanResources.Buffer buffer(int index, long bytes, int usage, boolean mapped) {
        BufferSlot old = index < buffers.size() ? buffers.get(index) : null;
        if (old != null && old.bytes == bytes && old.usage == usage && old.mapped == mapped) return old.value;
        var value = mapped ? resources.allocateMapped(Math.toIntExact(bytes), usage) : resources.allocateDevice(bytes, usage);
        var slot = new BufferSlot(value, bytes, usage, mapped);
        if (old == null) buffers.add(slot);
        else { buffers.set(index, slot); old.value.close(); }
        return value;
    }

    VulkanResources.Image image(int index, int format, int usage, int width, int height, int aspect, String label) {
        ImageSlot old = index < images.size() ? images.get(index) : null;
        if (old != null && old.format == format && old.usage == usage && old.width == width
            && old.height == height && old.aspect == aspect) return old.value;
        var value = resources.allocateImage(format, usage, width, height, aspect, label);
        var slot = new ImageSlot(value, format, usage, width, height, aspect);
        if (old == null) images.add(slot);
        else { images.set(index, slot); old.value.close(); }
        return value;
    }

    @Override public void close() {
        Throwable failure = null;
        for (var slot : images) failure = VulkanRetirement.attempt(failure, slot.value::close);
        for (var slot : buffers) failure = VulkanRetirement.attempt(failure, slot.value::close);
        images.clear(); buffers.clear();
        VulkanRetirement.finish(failure);
    }
}
