package dev.rt_render_experiment.engine;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


final class PipelineStatistics {
    private PipelineStatistics() {}
    static boolean enabled(VkDevice device) {
        return Boolean.getBoolean("rt_render_experiment.pipelineStatistics") && device.getCapabilities().VK_KHR_pipeline_executable_properties;
    }
    static int flags(VkDevice device) {
        if(!enabled(device))return 0;
        return KHRPipelineExecutableProperties.VK_PIPELINE_CREATE_CAPTURE_STATISTICS_BIT_KHR
            | (System.getProperty("rt_render_experiment.pipelineRepresentations")!=null?KHRPipelineExecutableProperties.VK_PIPELINE_CREATE_CAPTURE_INTERNAL_REPRESENTATIONS_BIT_KHR:0);
    }
    static void capture(VkDevice device, long pipeline, String entry) {
        if (!enabled(device)) return;
        try (var stack = MemoryStack.stackPush()) {
            var count = stack.callocInt(1);
            var info = VkPipelineInfoKHR.calloc(stack).sType$Default().pipeline(pipeline);
            require(KHRPipelineExecutableProperties.vkGetPipelineExecutablePropertiesKHR(device, info, count, null));
            var properties = VkPipelineExecutablePropertiesKHR.calloc(count.get(0), stack);
            for (int i=0;i<properties.capacity();i++) properties.get(i).sType$Default();
            require(KHRPipelineExecutableProperties.vkGetPipelineExecutablePropertiesKHR(device, info, count, properties));
            for (int i=0;i<count.get(0);i++) {
                System.out.println("RT_RENDER_EXPERIMENT_EXECUTABLE\t"+entry+"\t"+properties.get(i).nameString()+"\t"+properties.get(i).subgroupSize());
                var executable = VkPipelineExecutableInfoKHR.calloc(stack).sType$Default().pipeline(pipeline).executableIndex(i);
                var size = stack.callocInt(1);
                require(KHRPipelineExecutableProperties.vkGetPipelineExecutableStatisticsKHR(device, executable, size, null));
                var statistics = VkPipelineExecutableStatisticKHR.calloc(size.get(0), stack);
                for (int n=0;n<statistics.capacity();n++) statistics.get(n).sType$Default();
                require(KHRPipelineExecutableProperties.vkGetPipelineExecutableStatisticsKHR(device, executable, size, statistics));
                for (int n=0;n<size.get(0);n++) {
                    var stat = statistics.get(n);
                    String value = switch(stat.format()) {
                        case KHRPipelineExecutableProperties.VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_BOOL32_KHR -> Boolean.toString(stat.value().b32());
                        case KHRPipelineExecutableProperties.VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_FLOAT64_KHR -> Double.toString(stat.value().f64());
                        case KHRPipelineExecutableProperties.VK_PIPELINE_EXECUTABLE_STATISTIC_FORMAT_INT64_KHR -> Long.toString(stat.value().i64());
                        default -> Long.toUnsignedString(stat.value().u64());
                    };
                    System.out.println("RT_RENDER_EXPERIMENT_STAT\t"+entry+"\t"+stat.nameString()+"\t"+value+"\t"+stat.descriptionString());
                }
                representations(device,executable,entry,i);
            }
        }
    }
    private static void representations(VkDevice device,VkPipelineExecutableInfoKHR executable,String entry,int executableIndex) {
        String directory=System.getProperty("rt_render_experiment.pipelineRepresentations");if(directory==null)return;
        try(var stack=MemoryStack.stackPush()) {
            var count=stack.callocInt(1);
            require(KHRPipelineExecutableProperties.vkGetPipelineExecutableInternalRepresentationsKHR(device,executable,count,null));
            System.out.println("RT_RENDER_EXPERIMENT_REPRESENTATIONS\t"+entry+"\t"+count.get(0));
            if(count.get(0)==0)return;
            var representations=VkPipelineExecutableInternalRepresentationKHR.calloc(count.get(0),stack);
            for(int i=0;i<representations.capacity();i++)representations.get(i).sType$Default();
            require(KHRPipelineExecutableProperties.vkGetPipelineExecutableInternalRepresentationsKHR(device,executable,count,representations));
            var buffers=new java.util.ArrayList<java.nio.ByteBuffer>();
            try {
                for(int i=0;i<count.get(0);i++) {
                    var representation=representations.get(i);long bytes=representation.dataSize();
                    if(bytes<0 || bytes>64L*1024*1024)throw new IllegalStateException("Unreasonable pipeline representation size: "+bytes);
                    var buffer=org.lwjgl.system.MemoryUtil.memAlloc(Math.max(1,Math.toIntExact(bytes)));buffers.add(buffer);
                    org.lwjgl.system.MemoryUtil.memPutAddress(representation.address()+VkPipelineExecutableInternalRepresentationKHR.PDATA,org.lwjgl.system.MemoryUtil.memAddress(buffer));
                }
                require(KHRPipelineExecutableProperties.vkGetPipelineExecutableInternalRepresentationsKHR(device,executable,count,representations));
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(directory));
                for(int i=0;i<count.get(0);i++) {
                    var representation=representations.get(i);byte[] bytes=new byte[Math.toIntExact(representation.dataSize())];buffers.get(i).get(0,bytes);
                    var path=java.nio.file.Path.of(directory).resolve(entry+"-"+executableIndex+"-"+i+(representation.isText()?".txt":".bin"));
                    java.nio.file.Files.write(path,bytes);
                    System.out.println("RT_RENDER_EXPERIMENT_REPRESENTATION\t"+entry+"\t"+representation.nameString()+"\t"+bytes.length+"\t"+path);
                }
            } finally { for(var buffer:buffers)org.lwjgl.system.MemoryUtil.memFree(buffer); }
        } catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private static void require(int result) {
        if (result!=VK12.VK_SUCCESS) throw new IllegalStateException("Pipeline statistics query failed: "+result);
    }
}
