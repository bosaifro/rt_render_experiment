package dev.rt_render_experiment.reentry;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


public final class VulkanAdmission {
    private static final Map<Long, Boolean> ENABLED = new HashMap<>();
    private static volatile boolean captureEnabled;
    private static final List<String> EXTENSIONS = List.of(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
        KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME, KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME);
    private static final VulkanPNextStruct AS = new VulkanPNextStruct(KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
        VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct RQ = new VulkanPNextStruct(KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
        VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF);
    private static final List<VulkanFeature> FEATURES = List.of(
        new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,"shaderInt64",VkPhysicalDeviceFeatures.SHADERINT64),
        new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,"shaderStorageImageExtendedFormats",VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEEXTENDEDFORMATS),
        new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,"shaderStorageImageReadWithoutFormat",VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEREADWITHOUTFORMAT),
        new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT,"shaderStorageImageWriteWithoutFormat",VkPhysicalDeviceFeatures.SHADERSTORAGEIMAGEWRITEWITHOUTFORMAT),
        new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,"bufferDeviceAddress",VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS),
        new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,"runtimeDescriptorArray",VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY),
        new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,"descriptorBindingVariableDescriptorCount",VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGVARIABLEDESCRIPTORCOUNT),
        new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT,"shaderSampledImageArrayNonUniformIndexing",VkPhysicalDeviceVulkan12Features.SHADERSAMPLEDIMAGEARRAYNONUNIFORMINDEXING),
        new VulkanFeature(AS,"accelerationStructure",VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE),
        new VulkanFeature(RQ,"rayQuery",VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY));
    private VulkanAdmission() {}
    public static boolean extend(VulkanPhysicalDevice physical, Collection<String> extensions, Set<VulkanFeature> features) {
        if (Boolean.getBoolean("rt_render_experiment.disabled") || !RtRenderExperimentMixinPlugin.producerSupported()) return false;
        if (EXTENSIONS.stream().anyMatch(extension -> !physical.hasDeviceExtension(extension))) return false;
        try (var stack = MemoryStack.stackPush()) {
            var query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            for (var feature : FEATURES) feature.struct().findOrCreateStructInPNextChain(query.address(),stack);
            VK12.vkGetPhysicalDeviceFeatures2(physical.vkPhysicalDevice(),query);
            if (FEATURES.stream().anyMatch(feature -> !feature.get(query))) return false;
        }
        extensions.addAll(EXTENSIONS); features.addAll(FEATURES); return true;
    }
    public static void register(VkDevice device, boolean enabled) {
        ENABLED.put(device.address(),enabled);
        captureEnabled=ENABLED.containsValue(true);
        RtRenderExperimentClient.LOGGER.info("RT_RENDER_EXPERIMENT_DEVICE producerRenderer={} sameHostDevice=true",enabled);
    }
    public static boolean enabled(long device) { return ENABLED.getOrDefault(device,false); }
    public static boolean captureEnabled() { return captureEnabled; }
    public static void forget(long device) { ENABLED.remove(device);captureEnabled=ENABLED.containsValue(true); }
    public static HostExecution.Capabilities capabilities(VkDevice device,long identity) {
        if (!enabled(device.address())) throw new IllegalStateException("Host did not enable renderer features");
        try (var stack = MemoryStack.stackPush()) {
            var acceleration = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(),VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(acceleration.address()));
            return new HostExecution.Capabilities(identity,Set.of(HostExecution.Capability.BUFFER_ADDRESS,HostExecution.Capability.ACCELERATION_STRUCTURE,
                HostExecution.Capability.RAY_QUERY,HostExecution.Capability.SAMPLER_ANISOTROPY),
                Integer.toUnsignedLong(acceleration.minAccelerationStructureScratchOffsetAlignment()),acceleration.maxInstanceCount());
        }
    }
}
