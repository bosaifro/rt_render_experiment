package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanObjects;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;


final class FixtureHost implements AutoCloseable {
    private final List<String> errors=java.util.Collections.synchronizedList(new ArrayList<>());
    private final VkDebugUtilsMessengerCallbackEXT callback;
    final VkInstance instance;
    final int family;
    private final long messenger,allocator,pool,fence;
    final VkDevice device;
    final VkQueue queue;
    final VulkanResources resources;
    private long serial=1;
    private float timestampPeriod=1;

    double timestampPeriodNanoseconds() { return timestampPeriod; }
    FixtureHost() { this(true); }

    FixtureHost(boolean validationEnabled) {
        callback=VkDebugUtilsMessengerCallbackEXT.create((severity,type,data,user)-> {
            if ((severity&EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)!=0)
                errors.add(VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString());
            return VK12.VK_FALSE;
        });
        try (MemoryStack stack=MemoryStack.stackPush()) {
            var debug=VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default().pfnUserCallback(callback)
                .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT);
            var validation=VkValidationFeaturesEXT.calloc(stack).sType$Default().pNext(debug.address())
                .pEnabledValidationFeatures(stack.ints(EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT));
            var app=VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK12.VK_API_VERSION_1_2).pApplicationName(stack.UTF8("RtRenderExperiment production fixture host"));
            var instanceExtensions=new java.util.LinkedHashSet<String>();
            instanceExtensions.add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (validationEnabled) instanceExtensions.add(EXTValidationFeatures.VK_EXT_VALIDATION_FEATURES_EXTENSION_NAME);
            var instanceNames=stack.mallocPointer(instanceExtensions.size()); for(String name:instanceExtensions)instanceNames.put(stack.UTF8(name)); instanceNames.flip();
            var create=VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app).pNext(validationEnabled ? validation.address() : debug.address())
                .ppEnabledExtensionNames(instanceNames);
            if (validationEnabled) create.ppEnabledLayerNames(stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation")));
            var pointer=stack.callocPointer(1); ok(VK12.vkCreateInstance(create,null,pointer)); instance=new VkInstance(pointer.get(0),create);
            var handle=stack.callocLong(1); ok(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance,debug,null,handle)); messenger=handle.get(0);
            var count=stack.callocInt(1); ok(VK12.vkEnumeratePhysicalDevices(instance,count,null));
            var physicals=stack.callocPointer(count.get(0)); ok(VK12.vkEnumeratePhysicalDevices(instance,count,physicals));
            VkPhysicalDevice physical=null;
            for (int i=0;i<physicals.remaining();i++) {
                var candidate=new VkPhysicalDevice(physicals.get(i),instance);
                var query=VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
                var rt=VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default().pNext(query.address());
                VK12.vkGetPhysicalDeviceFeatures2(candidate,VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(rt.address()));
                if (rt.rayTracingPipeline() && query.rayQuery()) { physical=candidate; break; }
            }
            if (physical==null) throw new IllegalStateException("Fixture requires a Vulkan RT device");
            var properties=VkPhysicalDeviceProperties.calloc(stack); VK12.vkGetPhysicalDeviceProperties(physical,properties); timestampPeriod=properties.limits().timestampPeriod();
            var availableFeatures=VkPhysicalDeviceFeatures.calloc(stack);VK12.vkGetPhysicalDeviceFeatures(physical,availableFeatures);
            samplerAnisotropy=availableFeatures.samplerAnisotropy();
            System.out.println("Fixture device: "+properties.deviceNameString()+" driver="+Integer.toUnsignedString(properties.driverVersion())+" validation="+validationEnabled);
            VK12.vkGetPhysicalDeviceQueueFamilyProperties(physical,count,null);
            var families=VkQueueFamilyProperties.calloc(count.get(0),stack); VK12.vkGetPhysicalDeviceQueueFamilyProperties(physical,count,families);
            int family=-1;
            for (int i=0;i<families.remaining();i++) if ((families.get(i).queueFlags()&VK12.VK_QUEUE_GRAPHICS_BIT)!=0) { family=i; break; }
            if (family<0) throw new IllegalStateException("No graphics recording lane");
            this.family=family;
            var drawParameters=VkPhysicalDeviceShaderDrawParametersFeatures.calloc(stack).sType$Default().shaderDrawParameters(true);
            var hostReset=VkPhysicalDeviceHostQueryResetFeatures.calloc(stack).sType$Default().hostQueryReset(true).pNext(drawParameters.address());
            var rendering=VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default().dynamicRendering(true).pNext(hostReset.address());
            var sync=VkPhysicalDeviceSynchronization2FeaturesKHR.calloc(stack).sType$Default().synchronization2(true).pNext(rendering.address());
            var descriptors=VkPhysicalDeviceDescriptorIndexingFeatures.calloc(stack).sType$Default().runtimeDescriptorArray(true)
                .descriptorBindingVariableDescriptorCount(true).shaderSampledImageArrayNonUniformIndexing(true).pNext(sync.address());
            var addresses=VkPhysicalDeviceBufferDeviceAddressFeatures.calloc(stack).sType$Default().bufferDeviceAddress(true).pNext(descriptors.address());
            var query=VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default().rayQuery(true).pNext(addresses.address());
            var rt=VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default().rayTracingPipeline(true).pNext(query.address());
            var acceleration=VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default().accelerationStructure(true).pNext(rt.address());
            var deviceExtensions=new java.util.LinkedHashSet<String>();
            deviceExtensions.addAll(List.of(KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
                KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME,
                KHRSynchronization2.VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME,KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME,KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME));
            var executable=VkPhysicalDevicePipelineExecutablePropertiesFeaturesKHR.calloc(stack).sType$Default().pipelineExecutableInfo(true).pNext(acceleration.address());
            boolean captureExecutable=Boolean.getBoolean("rt_render_experiment.pipelineStatistics");
            if(captureExecutable)deviceExtensions.add(KHRPipelineExecutableProperties.VK_KHR_PIPELINE_EXECUTABLE_PROPERTIES_EXTENSION_NAME);
            var deviceNames=stack.mallocPointer(deviceExtensions.size()); for(String name:deviceExtensions)deviceNames.put(stack.UTF8(name)); deviceNames.flip();
            var deviceCreate=VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(captureExecutable?executable.address():acceleration.address())
                .pEnabledFeatures(VkPhysicalDeviceFeatures.calloc(stack).shaderInt64(true).shaderStorageImageExtendedFormats(true)
                    .shaderStorageImageReadWithoutFormat(true).shaderStorageImageWriteWithoutFormat(true).samplerAnisotropy(samplerAnisotropy))
                .pQueueCreateInfos(VkDeviceQueueCreateInfo.calloc(1,stack).sType$Default().queueFamilyIndex(family).pQueuePriorities(stack.floats(1)))
                .ppEnabledExtensionNames(deviceNames);
            ok(VK12.vkCreateDevice(physical,deviceCreate,null,pointer)); device=new VkDevice(pointer.get(0),physical,deviceCreate);
            VK12.vkGetDeviceQueue(device,family,0,pointer); queue=new VkQueue(pointer.get(0),device);
            var allocatorCreate=VmaAllocatorCreateInfo.calloc(stack).flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                .instance(instance).physicalDevice(physical).device(device).vulkanApiVersion(VK12.VK_API_VERSION_1_2)
                .pVulkanFunctions(VmaVulkanFunctions.calloc(stack).set(instance,device));
            ok(Vma.vmaCreateAllocator(allocatorCreate,pointer)); allocator=pointer.get(0);
            resources=new VulkanResources(device,allocator,new ResourceViews.Submission(1,serial,0,0));
            ok(VK12.vkCreateCommandPool(device,VkCommandPoolCreateInfo.calloc(stack).sType$Default().queueFamilyIndex(family)
                .flags(VK12.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),null,handle)); pool=handle.get(0);
            ok(VK12.vkCreateFence(device,VkFenceCreateInfo.calloc(stack).sType$Default(),null,handle)); fence=handle.get(0);
        }
    }
    final boolean samplerAnisotropy;
    VkCommandBuffer begin() {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            var pointer=stack.callocPointer(1);
            ok(VK12.vkAllocateCommandBuffers(device,VkCommandBufferAllocateInfo.calloc(stack).sType$Default().commandPool(pool)
                .level(VK12.VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1),pointer));
            var command=new VkCommandBuffer(pointer.get(0),device);
            ok(VK12.vkBeginCommandBuffer(command,VkCommandBufferBeginInfo.calloc(stack).sType$Default())); return command;
        }
    }
    VulkanResources.Buffer copy(VkCommandBuffer command,VulkanResources.Buffer source) {
        var target=resources.allocateReadback(Math.toIntExact(source.view().length())); target.markUsed();
        try (MemoryStack stack=MemoryStack.stackPush()) {
            VulkanBarriers.record(command,stack,new VulkanBarriers.Scope(KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_WRITE_BIT,VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK13.VK_ACCESS_2_TRANSFER_READ_BIT));
            VK12.vkCmdCopyBuffer(command,source.view().handle(),target.view().handle(),VkBufferCopy.calloc(1,stack).size(source.view().length()));
            VulkanBarriers.record(command,stack,VulkanBarriers.COUNTER_COPY_TO_HOST);
        }
        return target;
    }
    long submit(VkCommandBuffer command) {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            ok(VK12.vkEndCommandBuffer(command));
            ok(VK12.vkResetFences(device,fence));
            ok(VK12.vkQueueSubmit(queue,VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(command)),fence));
            long submitted=serial++;
            resources.observe(new ResourceViews.Submission(1,serial,submitted,resources.completed()));
            return submitted;
        }
    }
    void abandon(VkCommandBuffer command) { VK12.vkFreeCommandBuffers(device,pool,command); }
    void complete(VkCommandBuffer command,long submitted) {
        ok(VK12.vkWaitForFences(device,fence,true,30_000_000_000L));
        resources.observe(new ResourceViews.Submission(1,serial,submitted,submitted));
        VK12.vkFreeCommandBuffers(device,pool,command);
        assertValidation();
    }
    ByteBuffer read(VulkanResources.Buffer buffer) {
        if (resources.completed()<buffer.lastUse()) throw new IllegalStateException("Readback is not complete");
        try (var mapping=buffer.map(0,buffer.view().length())) {
            var copy=ByteBuffer.allocate(mapping.data().remaining()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            copy.put(mapping.data()).flip(); return copy;
        }
    }
    void assertValidation() { if (!errors.isEmpty()) throw new AssertionError("Vulkan validation: "+String.join("\n",errors)); }

    long[] allocationBytes() {
        try (MemoryStack stack=MemoryStack.stackPush()) {
            var total=VmaTotalStatistics.calloc(stack); Vma.vmaCalculateStatistics(allocator,total);
            var stats=total.total().statistics(); return new long[]{stats.allocationBytes(),stats.blockBytes()};
        }
    }
    private static void ok(int result) { VulkanObjects.requireSuccess(result,"Fixture host Vulkan operation"); }
    @Override public void close() {
        ok(VK12.vkDeviceWaitIdle(device)); resources.closeAfterHost();
        VK12.vkDestroyFence(device,fence,null); VK12.vkDestroyCommandPool(device,pool,null);
        Vma.vmaDestroyAllocator(allocator); VK12.vkDestroyDevice(device,null);
        EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance,messenger,null); VK12.vkDestroyInstance(instance,null); callback.close();
        assertValidation();
    }
}
