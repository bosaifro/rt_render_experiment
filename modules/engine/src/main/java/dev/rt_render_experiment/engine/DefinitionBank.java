package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.engine.abi.R2Abi;

import java.util.List;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanDescriptors;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRRayTracingPipeline;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkWriteDescriptorSet;


final class DefinitionBank implements AutoCloseable {
    enum Kind {
        MATERIALS(ShaderModules.Role.COMPILE_MATERIALS,R2Abi.MATERIAL_COUNT,R2Abi.MaterialRecord.SIZE),
        ATMOSPHERE(ShaderModules.Role.COMPILE_ATMOSPHERE,Math.multiplyExact(R2Abi.ATMOSPHERE_WIDTH,R2Abi.ATMOSPHERE_HEIGHT),R2Abi.AtmosphereCellRecord.SIZE);
        final ShaderModules.Role role;
        final int records,stride;
        Kind(ShaderModules.Role role,int records,int stride) { this.role=role;this.records=records;this.stride=stride; }
    }
    private final Kind kind;
    private final VulkanResources resources;
    private final ShaderModules shaders;
    private ComputeProgram program;
    DefinitionBank(VulkanResources resources,ShaderModules shaders,Kind kind) { this.resources=resources;this.shaders=shaders;this.kind=kind; }
    Version prepare(Version published) {
        if(published!=null && published.owner!=this)throw new IllegalArgumentException("Foreign definition generation");
        return published==null?new Version():published.retain();
    }
    int record(VkCommandBuffer command,Version version) {
        if(version.owner!=this || version.owners<=0)throw new IllegalArgumentException("Foreign or retired definition generation");
        if(version.recorded)return 0;
        if(version.owners<=0 || version.recording!=resources.recording() || command.getDevice()!=resources.device())
            throw new IllegalStateException("Invalid definition compilation window");
        if(program==null)program=new ComputeProgram(resources,shaders,List.of(shaders.entry(kind.role)),
            List.of(VulkanDescriptors.storageBuffer("Definitions",VK12.VK_SHADER_STAGE_COMPUTE_BIT)));
        version.markUsed();
        try(var stack=MemoryStack.stackPush()) {
            var writes=VkWriteDescriptorSet.calloc(1,stack);
            VulkanDescriptors.writeStorageBuffer(writes.get(0),stack,0,version.buffer.view());
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command,VK12.VK_PIPELINE_BIND_POINT_COMPUTE,program.layout(),0,writes);
            program.dispatch(command,0,(kind.records+63)/64,1);
            VulkanBarriers.record(command,stack,new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK13.VK_ACCESS_2_SHADER_WRITE_BIT,
                KHRRayTracingPipeline.VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR|VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK13.VK_ACCESS_2_SHADER_READ_BIT));
        }
        version.recorded=true;return kind.records;
    }
    final class Version implements AutoCloseable {
        private final DefinitionBank owner=DefinitionBank.this;
        private final long recording=resources.recording();
        private final VulkanResources.Buffer buffer=resources.allocateDevice(Math.multiplyExact(kind.records,kind.stride),
            VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT|VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        private int owners=1;
        private boolean recorded,published;
        private Version retain() { if(owners<=0 || !published)throw new IllegalStateException("Definitions are not published");owners=Math.incrementExact(owners);return this; }
        void submitted(long serial) {
            if(owners<=0 || !recorded || serial<recording || !published && serial!=recording)throw new IllegalStateException("Definition initialization lacks its exact submission");
            published=true;
        }
        void markUsed() { if(owners<=0)throw new IllegalStateException("Retired definitions");buffer.markUsed(); }
        VulkanResources.Buffer buffer() { if(owners<=0 || !recorded)throw new IllegalStateException("Definitions are not recorded");return buffer; }
        long bytes() { return (long)kind.records*kind.stride; }
        @Override public void close() { if(owners<=0)throw new IllegalStateException("Definitions released twice");if(--owners==0)buffer.close(); }
    }
    @Override public void close() { if(program!=null)program.close(); }
}
