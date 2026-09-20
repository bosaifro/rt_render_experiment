package dev.rt_render_experiment.reentry.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import java.util.Collection;
import java.util.Set;
import dev.rt_render_experiment.reentry.VulkanAdmission;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = VulkanBackend.class, remap = false)
public abstract class VulkanBackendMixin {
    @WrapOperation(method = "createDevice", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"), require = 1, allow = 1)
    private VkDevice rt_render_experiment$features(Collection<String> extensions, VulkanPhysicalDevice physical, Set<VulkanFeature> features, Operation<VkDevice> original) {
        boolean enabled = VulkanAdmission.extend(physical,extensions,features);
        VkDevice device = original.call(extensions,physical,features);
        VulkanAdmission.register(device,enabled);
        return device;
    }
    @WrapOperation(method = "createVma", at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/vma/Vma;vmaCreateAllocator(Lorg/lwjgl/util/vma/VmaAllocatorCreateInfo;Lorg/lwjgl/PointerBuffer;)I"), require = 1, allow = 1)
    private static int rt_render_experiment$addressAllocator(VmaAllocatorCreateInfo info, PointerBuffer result, Operation<Integer> original) {
        if (VulkanAdmission.enabled(info.device())) info.flags(info.flags() | Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT);
        return original.call(info,result);
    }
}
