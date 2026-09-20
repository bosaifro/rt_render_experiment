package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.TextureInputs;
import dev.rt_render_experiment.engine.abi.R2Abi;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanDescriptors;
import dev.rt_render_experiment.vulkan.VulkanResources;
import dev.rt_render_experiment.vulkan.VulkanSamplers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


final class TextureImporter implements AutoCloseable {
    private final VulkanResources resources;
    private final ComputeProgram pipeline;
    TextureImporter(VulkanResources resources,ShaderModules shaders) {
        this.resources=resources;
        pipeline=new ComputeProgram(resources,shaders,List.of(shaders.entry(ShaderModules.Role.TEXTURE_COPY),shaders.entry(ShaderModules.Role.TEXTURE_COPY_FLOAT)),List.of(
            VulkanDescriptors.uniform("Copy",VK12.VK_SHADER_STAGE_COMPUTE_BIT),VulkanDescriptors.sampler("Source",VK12.VK_SHADER_STAGE_COMPUTE_BIT),
            VulkanDescriptors.storageBuffer("Pixels",VK12.VK_SHADER_STAGE_COMPUTE_BIT)));
    }
    static void validate(TextureInputs.GpuTexture texture) {
        var image=texture.source().view();
        destinationFormat(image.format());
        if(image.layerCount()!=1 || image.aspectMask()!=VK12.VK_IMAGE_ASPECT_COLOR_BIT
            || (image.usage()&VK12.VK_IMAGE_USAGE_SAMPLED_BIT)==0
            || image.layout()!=VK12.VK_IMAGE_LAYOUT_GENERAL && image.layout()!=VK12.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            throw new IllegalArgumentException("Unadmitted sampled texture format/subresources/layout");
        int maximum=32-Integer.numberOfLeadingZeros(Math.max(image.width(),image.height()));
        if(image.mipCount()>maximum)throw new IllegalArgumentException("Invalid sampled texture mip range");
    }
    static int destinationFormat(int source) {
        if(source==VK12.VK_FORMAT_R8G8B8A8_UNORM)return source;
        return switch(source) {
            case VK12.VK_FORMAT_R8_UNORM,VK12.VK_FORMAT_R8_SNORM,VK12.VK_FORMAT_R8G8_UNORM,VK12.VK_FORMAT_R8G8_SNORM,
                VK12.VK_FORMAT_R8G8B8_UNORM,VK12.VK_FORMAT_R8G8B8_SNORM,VK12.VK_FORMAT_R8G8B8A8_SNORM,VK12.VK_FORMAT_B8G8R8A8_UNORM,
                VK12.VK_FORMAT_R16_UNORM,VK12.VK_FORMAT_R16_SNORM,VK12.VK_FORMAT_R16_SFLOAT,
                VK12.VK_FORMAT_R16G16_UNORM,VK12.VK_FORMAT_R16G16_SNORM,VK12.VK_FORMAT_R16G16_SFLOAT,
                VK12.VK_FORMAT_R16G16B16_UNORM,VK12.VK_FORMAT_R16G16B16_SNORM,VK12.VK_FORMAT_R16G16B16_SFLOAT,
                VK12.VK_FORMAT_R16G16B16A16_UNORM,VK12.VK_FORMAT_R16G16B16A16_SNORM,VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                VK12.VK_FORMAT_R32_SFLOAT,VK12.VK_FORMAT_R32G32_SFLOAT,VK12.VK_FORMAT_R32G32B32_SFLOAT,VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32,VK12.VK_FORMAT_A2R10G10B10_UNORM_PACK32,VK12.VK_FORMAT_B10G11R11_UFLOAT_PACK32
                -> VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
            default -> throw new IllegalArgumentException("Unadmitted sampled texture numeric format: "+source);
        };
    }
    static int pixelBytes(int destination) { return destination==VK12.VK_FORMAT_R8G8B8A8_UNORM?4:16; }
    static void validateDestination(VulkanResources resources,int format,TextureInputs.Sampling sampling) {
        validateSampling(resources,format,sampling,true);
    }
    static void validateSampling(VulkanResources resources,int format,TextureInputs.Sampling sampling,boolean destination) {
        try(var stack=MemoryStack.stackPush()) {
            var properties=VkFormatProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceFormatProperties(resources.device().getPhysicalDevice(),format,properties);
            int required=VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|(destination?VK12.VK_FORMAT_FEATURE_TRANSFER_DST_BIT:0);
            if(sampling.minification()==TextureInputs.Filter.LINEAR || sampling.magnification()==TextureInputs.Filter.LINEAR
                || sampling.mipmap()==TextureInputs.Filter.LINEAR || sampling.anisotropy()>1)required|=VK12.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if((properties.optimalTilingFeatures()&required)!=required)throw new IllegalArgumentException("Device cannot preserve the requested sampled texture/filter contract");
        }
    }
    void validate(HostExecution.Window window,TextureInputs.GpuTexture texture) {
        validate(texture);
        texture.requirePreparationWindow(window);
        if(window.device()!=resources.deviceIdentity() || window.recording()!=resources.recording())
            throw new IllegalArgumentException("Texture copy requires its granted scene-preparation window");
    }
    void record(VkCommandBuffer command,TextureInputs.GpuTexture texture,VulkanResources.Buffer output) {
        if(command.getDevice()!=resources.device())throw new IllegalArgumentException("Foreign texture-copy command device");
        ResourceViews.Image source=texture.source().view();
        long sampler=resources.samplers().get(new VulkanSamplers.State(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            VK12.VK_FILTER_NEAREST,VK12.VK_FILTER_NEAREST,VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST,source.mipCount()-1,1));
        resources.samplers().markUsed(sampler);output.markUsed();
        try(var stack=MemoryStack.stackPush()) {
            VulkanBarriers.record(command,stack,new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK13.VK_ACCESS_2_MEMORY_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK13.VK_ACCESS_2_SHADER_READ_BIT|VK13.VK_ACCESS_2_SHADER_WRITE_BIT));
            int width=source.width(),height=source.height(),offset=0;
            for(int mip=0;mip<source.mipCount();mip++) {
                try(var uniform=resources.allocateMapped(R2Abi.TextureCopyRecord.SIZE,VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)) {
                    try(var mapping=uniform.map(0,R2Abi.TextureCopyRecord.SIZE)) {
                        R2Abi.TextureCopyRecord.extentMipOffset(mapping.data().order(java.nio.ByteOrder.LITTLE_ENDIAN),0,width,height,mip,offset);
                    }
                    uniform.markUsed();
                    var writes=VkWriteDescriptorSet.calloc(3,stack);
                    VulkanDescriptors.writeUniform(writes.get(0),stack,0,uniform.view());
                    VulkanDescriptors.writeCombined(writes.get(1),stack,1,source,sampler);
                    VulkanDescriptors.writeStorageBuffer(writes.get(2),stack,2,output.view());
                    KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command,VK12.VK_PIPELINE_BIND_POINT_COMPUTE,pipeline.layout(),0,writes);
                    pipeline.dispatch(command,destinationFormat(source.format())==VK12.VK_FORMAT_R8G8B8A8_UNORM?0:1,(width+7)/8,(height+7)/8);
                }
                offset=Math.addExact(offset,Math.multiplyExact(width,height));width=Math.max(1,width/2);height=Math.max(1,height/2);
            }
            VulkanBarriers.record(command,stack,new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK13.VK_ACCESS_2_SHADER_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK13.VK_ACCESS_2_TRANSFER_READ_BIT));
        }
    }
    @Override public void close() { pipeline.close(); }
}
