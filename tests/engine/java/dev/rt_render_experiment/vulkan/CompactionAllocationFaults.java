package dev.rt_render_experiment.vulkan;


public final class CompactionAllocationFaults {
    private CompactionAllocationFaults() {}
    public static void next(VulkanResources resources,int step) { resources.failAccelerationAllocation(0,step); }
    public static void query(VulkanResources resources) { resources.failCompactionQueryAllocation(); }
}
