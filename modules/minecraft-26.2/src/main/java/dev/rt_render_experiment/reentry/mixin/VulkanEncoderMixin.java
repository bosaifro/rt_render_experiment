package dev.rt_render_experiment.reentry.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanQueue;
import dev.rt_render_experiment.reentry.HostDevice;
import dev.rt_render_experiment.reentry.HostSubmission;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=VulkanCommandEncoder.class,remap=false)
public abstract class VulkanEncoderMixin implements HostSubmission {
    @Shadow @Final private VulkanDevice device;
    @Shadow private long currentSubmitIndex;
    @Unique private long rt_render_experiment$submitted;
    @Override public long rt_render_experiment$lastSubmitted() { return rt_render_experiment$submitted; }
    @Inject(method="submit",at=@At("HEAD"))
    private void rt_render_experiment$frameTiming(CallbackInfo callback) { dev.rt_render_experiment.reentry.HostFrameTiming.beforeSubmit(); }
    @WrapOperation(method="submit",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vulkan/VulkanQueue$Submission;close()V"),require=1,allow=1)
    private void rt_render_experiment$accepted(VulkanQueue.Submission submission,Operation<Void> original) {
        original.call(submission);rt_render_experiment$submitted=currentSubmitIndex;HostDevice.submitted(device,currentSubmitIndex);
    }
    @Inject(method="awaitSubmitCompletion",at=@At("RETURN"))
    private void rt_render_experiment$completed(long serial,long timeout,CallbackInfoReturnable<Boolean> callback) {
        if(callback.getReturnValue())HostDevice.completed(device);
    }
}
