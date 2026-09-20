package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.vulkan.VulkanDescriptors;
import dev.rt_render_experiment.vulkan.VulkanObjects;
import dev.rt_render_experiment.vulkan.VulkanResources;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


final class ComputeProgram implements AutoCloseable {
    private final VulkanResources resources;
    private final long descriptorLayout,layout;
    private final long[] pipelines;
    private final List<String> entries;
    private long lastUse;
    private boolean closed;
    ComputeProgram(VulkanResources resources,ShaderModules shaders,List<String> entries,List<VulkanDescriptors.Binding> bindings) {
        this(resources,shaders,entries,bindings,0L);
    }

    ComputeProgram(VulkanResources resources,ShaderModules shaders,List<String> entries,List<VulkanDescriptors.Binding> bindings,long textureLayout) {
        this.entries=List.copyOf(entries); this.resources=resources; this.pipelines=new long[entries.size()]; var device=resources.device(); long descriptors=0,pipelineLayout=0;
        try (MemoryStack stack=MemoryStack.stackPush()) {
            var declared = shaders instanceof R2ShaderPackage
                ? dev.rt_render_experiment.engine.abi.R2Resources.forEntry(entries.getFirst(), VK12.VK_SHADER_STAGE_COMPUTE_BIT) : bindings;
            descriptors=VulkanDescriptors.createPushLayout(device,"RtRenderExperiment "+entries.getFirst(),declared);
            var handle=stack.callocLong(1);
            VulkanObjects.requireSuccess(VK12.vkCreatePipelineLayout(device,VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(textureLayout==0L?stack.longs(descriptors):stack.longs(descriptors,textureLayout)),null,handle),"Create compute family layout");
            pipelineLayout=handle.get(0);
            for(int i=0;i<entries.size();i++) {
                long module=VulkanObjects.shaderModule(device,shaders.module(entries.get(i)),entries.get(i));
                try {
                    var create=VkComputePipelineCreateInfo.calloc(1,stack).sType$Default().layout(pipelineLayout).flags(PipelineStatistics.flags(device));
                    create.stage().sType$Default().stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                    VulkanObjects.requireSuccess(VK12.vkCreateComputePipelines(device,resources.compilationCache(shaders.executionIdentity()),create,null,handle),"Create "+entries.get(i)); pipelines[i]=handle.get(0); PipelineStatistics.capture(device,pipelines[i],entries.get(i));
                } finally { VK12.vkDestroyShaderModule(device,module,null); }
            }
            descriptorLayout=descriptors; layout=pipelineLayout;
        } catch(RuntimeException | Error failure) {
            for(long pipeline:pipelines)if(pipeline!=0)VK12.vkDestroyPipeline(device,pipeline,null);
            if(pipelineLayout!=0)VK12.vkDestroyPipelineLayout(device,pipelineLayout,null);
            if(descriptors!=0)VK12.vkDestroyDescriptorSetLayout(device,descriptors,null);
            throw failure;
        }
    }
    long layout() { return layout; }
    void dispatch(VkCommandBuffer command,int entry,int x,int y) {
        if(closed || command.getDevice()!=resources.device())throw new IllegalStateException("Invalid compute family recording");
        lastUse=resources.recording(); VK12.vkCmdBindPipeline(command,VK12.VK_PIPELINE_BIND_POINT_COMPUTE,pipelines[entry]); ComputeDispatch.record(resources, command,entries.get(entry),x,y);
    }
    void dispatchIndirect(VkCommandBuffer command,int entry,VulkanResources.Buffer arguments,long offset) {
        if(closed||command.getDevice()!=resources.device()||offset<0||offset+12>arguments.view().length())throw new IllegalStateException("Invalid indirect recording");
        lastUse=resources.recording();arguments.markUsed();VK12.vkCmdBindPipeline(command,VK12.VK_PIPELINE_BIND_POINT_COMPUTE,pipelines[entry]);
        ComputeDispatch.recordIndirect(resources,command,entries.get(entry),arguments.view().handle(),arguments.view().offset()+offset);
    }
    void dispatchPixels(VkCommandBuffer command, int entry, int width, int height) {
        String name = entries.get(entry);
        dispatch(command, entry, Math.ceilDiv(width, dev.rt_render_experiment.engine.abi.R2Resources.workgroupX(name)),
            Math.ceilDiv(height, dev.rt_render_experiment.engine.abi.R2Resources.workgroupY(name)));
    }
    @Override public void close() {
        if(closed)return;
        resources.retirePrograms(lastUse,pipelines,new long[]{layout},new long[]{descriptorLayout});
        closed=true;
    }
}
