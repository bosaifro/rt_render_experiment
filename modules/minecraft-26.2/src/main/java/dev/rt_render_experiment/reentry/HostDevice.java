package dev.rt_render_experiment.reentry;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.vulkan.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongConsumer;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.reentry.mixin.VulkanEncoderAccessor;
import dev.rt_render_experiment.vulkan.VulkanResources;
import dev.rt_render_experiment.vulkan.VulkanRetirement;
import org.lwjgl.vulkan.VK12;


public final class HostDevice {
    private record Pending(long recording,LongConsumer submitted,Runnable retire) {}
    private static final Map<VulkanDevice,HostDevice> DEVICES=new IdentityHashMap<>();
    private static long nextIdentity=1;
    public final VulkanDevice device;
    public final VulkanResources resources;
    private final Map<GpuBuffer,ResourceViews.ResourceId> buffers=new WeakHashMap<>();
    private final Map<GpuTextureView,ResourceViews.ResourceId> images=new WeakHashMap<>();
    private final Map<GpuTextureView,Map<GpuSampler,dev.rt_render_experiment.contract.SceneInputs.Key>> textureKeys=new WeakHashMap<>();
    private final List<Pending> awaiting=new ArrayList<>(),inFlight=new ArrayList<>();
    private final List<Runnable> owners=new ArrayList<>();
    private boolean closing;
    private HostDevice(VulkanDevice device) {
        this.device=device;
        long identity=nextIdentity++;
        var encoder=(VulkanEncoderAccessor)device.createCommandEncoder();
        resources=new VulkanResources(device.vkDevice(),device.vma(),new ResourceViews.Submission(identity,encoder.rt_render_experiment$recording(),
            ((HostSubmission)encoder).rt_render_experiment$lastSubmitted(),encoder.rt_render_experiment$completed()));
    }
    public static HostDevice of(VulkanDevice device) {
        if(!VulkanAdmission.enabled(device.vkDevice().address()))throw new IllegalStateException("Host lacks producer renderer capabilities");
        var result=DEVICES.computeIfAbsent(device,HostDevice::new);result.observe();return result;
    }
    public void own(Runnable close) { if(closing)throw new IllegalStateException("Closing host device");owners.add(close); }
    public void track(long recording,LongConsumer submitted,Runnable retire) {
        if(closing || recording!=resources.recording())throw new IllegalArgumentException("Foreign frame submission reservation");
        awaiting.add(new Pending(recording,submitted,retire));
    }
    public long recording() { return resources.recording(); }
    public long identity() { return resources.deviceIdentity(); }
    public boolean hasUnsubmittedFrames() { return !awaiting.isEmpty(); }
    public void observe() {
        var encoder=(VulkanEncoderAccessor)device.createCommandEncoder();
        resources.observe(new ResourceViews.Submission(identity(),encoder.rt_render_experiment$recording(),((HostSubmission)encoder).rt_render_experiment$lastSubmitted(),encoder.rt_render_experiment$completed()));
        Throwable failure=null;
        for(var iterator=inFlight.iterator();iterator.hasNext();) {
            var pending=iterator.next();if(pending.recording<=resources.completed()) { iterator.remove();failure=VulkanRetirement.attempt(failure,pending.retire); }
        }
        VulkanRetirement.finish(failure);
    }
    public static void submitted(VulkanDevice device,long serial) {
        var owner=DEVICES.get(device);if(owner==null)return;
        var encoder=(VulkanEncoderAccessor)device.createCommandEncoder();
        owner.resources.observe(new ResourceViews.Submission(owner.identity(),serial+1,serial,encoder.rt_render_experiment$completed()));
        for(var iterator=owner.awaiting.iterator();iterator.hasNext();) {
            var pending=iterator.next();
            if(pending.recording==serial) { iterator.remove();owner.inFlight.add(pending);pending.submitted.accept(serial); }
            else if(pending.recording<serial)throw new IllegalStateException("Missed host submission event");
        }
    }
    public static void completed(VulkanDevice device) { var owner=DEVICES.get(device);if(owner!=null && !owner.closing)owner.observe(); }
    public ResourceViews.Buffer buffer(GpuBuffer buffer,long offset,long length) {
        if(buffer.isClosed() || !(buffer instanceof VulkanGpuBuffer vk))throw new IllegalArgumentException("Unavailable producer Vulkan buffer");
        var id=buffers.computeIfAbsent(buffer,ignored->resources.reserveIdentity());
        return new ResourceViews.Buffer(id,identity(),vk.vkBuffer(),1,buffer.size(),0,buffer.size(),0,VulkanConst.bufferUsageToVk(buffer.usage())).slice(offset,length);
    }
    public ResourceViews.Image image(GpuTextureView view) {
        if(view.isClosed() || view.texture().isClosed() || !(view instanceof VulkanGpuTextureView vk)
            || !(view.texture() instanceof HostTexture facts) || facts.rt_render_experiment$device()!=device)
            throw new IllegalArgumentException("Unavailable or foreign producer texture");
        var texture=(VulkanGpuTexture)view.texture();var id=images.computeIfAbsent(view,ignored->resources.reserveIdentity());
        return new ResourceViews.Image(id,identity(),texture.vkImage(),vk.vkImageView(),1,facts.rt_render_experiment$revision(),
            VulkanConst.toVk(texture.getFormat()),view.getWidth(0),view.getHeight(0),view.baseMipLevel(),view.mipLevels(),0,
            (texture.usage()&16)!=0?6:1,VulkanConst.formatAspectMask(texture.getFormat()),VulkanConst.textureUsageToVk(texture.usage(),texture.getFormat())|facts.rt_render_experiment$extraUsage(),VK12.VK_IMAGE_LAYOUT_GENERAL);
    }
    public dev.rt_render_experiment.contract.SceneInputs.Key textureKey(GpuTextureView view,GpuSampler sampler) {
        return textureKeys.computeIfAbsent(view,ignored->new WeakHashMap<>()).computeIfAbsent(sampler,
            ignored->new dev.rt_render_experiment.contract.SceneInputs.Key(identity()^0x544558L,resources.reserveIdentity().value()));
    }
    public static void beforeDestroy(VulkanDevice device) {
        var owner=DEVICES.get(device);if(owner==null)return;owner.closing=true;Throwable failure=null;
        for(var pending:owner.awaiting)failure=VulkanRetirement.attempt(failure,pending.retire);
        for(var pending:owner.inFlight)failure=VulkanRetirement.attempt(failure,pending.retire);
        owner.awaiting.clear();owner.inFlight.clear();
        for(int i=owner.owners.size()-1;i>=0;i--)failure=VulkanRetirement.attempt(failure,owner.owners.get(i));
        owner.owners.clear();VulkanRetirement.finish(failure);
    }
    public static void afterDestroy(VulkanDevice device) {
        var owner=DEVICES.remove(device);
        try { if(owner!=null)owner.resources.closeAfterHost(); }
        finally { VulkanAdmission.forget(device.vkDevice().address()); }
    }
}
