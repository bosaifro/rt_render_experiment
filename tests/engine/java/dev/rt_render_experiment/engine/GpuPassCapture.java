package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;


final class GpuPassCapture implements AutoCloseable, ComputeDispatch.Observer {
    private final FixtureHost host;
    private final long pool;
    private final List<String> entries = new ArrayList<>();
    GpuPassCapture(FixtureHost host) {
        this.host = host;
        try (var stack = MemoryStack.stackPush()) {
            var handle = stack.mallocLong(1);
            int result = VK12.vkCreateQueryPool(host.device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                .queryType(VK12.VK_QUERY_TYPE_TIMESTAMP).queryCount(256), null, handle);
            if (result != VK12.VK_SUCCESS) throw new IllegalStateException("Create pass queries: " + result);
            pool = handle.get(0);
        }
        ComputeDispatch.observe(this);
    }
    @Override public void before(VkCommandBuffer command, String entry, int x, int y) {
        if (entries.isEmpty()) VK12.vkCmdResetQueryPool(command, pool, 0, 256);
        if (entries.size() >= 128) throw new IllegalStateException("Pass query capacity exceeded");
        VK12.vkCmdWriteTimestamp(command, VK12.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, entries.size() * 2);
        System.out.println("RT_RENDER_EXPERIMENT_DISPATCH\t"+entry+"\t"+x+"\t"+y+"\t1");
        entries.add(entry);
    }
    @Override public void after(VkCommandBuffer command) {
        VK12.vkCmdWriteTimestamp(command, VK12.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, entries.size() * 2 - 1);
    }
    void report(String scene, int frame, String phase) {
        try (var stack = MemoryStack.stackPush()) {
            var values = stack.mallocLong(entries.size() * 2);
            int result = VK12.vkGetQueryPoolResults(host.device, pool, 0, values.remaining(), values, 8, VK12.VK_QUERY_RESULT_64_BIT);
            if (result != VK12.VK_SUCCESS) throw new IllegalStateException("Pass queries incomplete: " + result);
            for (int i = 0; i < entries.size(); i++) {
                double ms = (values.get(i * 2 + 1) - values.get(i * 2)) * host.timestampPeriodNanoseconds() / 1_000_000.0;
                System.out.printf(Locale.ROOT, "PASS_SAMPLE %s,%d,%s,%s,%d,%.9f%n", scene, frame, phase, entries.get(i), i, ms);
            }
        }
        entries.clear();
    }
    @Override public void close() {
        ComputeDispatch.observe(null);
        VK12.vkDestroyQueryPool(host.device, pool, null);
    }
}
