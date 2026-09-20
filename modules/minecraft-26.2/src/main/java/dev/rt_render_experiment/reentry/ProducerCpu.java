package dev.rt_render_experiment.reentry;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.LongAdder;


public final class ProducerCpu {
    private static final boolean ENABLED=Boolean.getBoolean("rt_render_experiment.timings");
    private static final ThreadMXBean THREADS=ManagementFactory.getThreadMXBean();
    private static final LongAdder TERRAIN_CPU=new LongAdder(),TERRAIN_JOBS=new LongAdder();
    private static long featureCpu;
    private ProducerCpu() {}
    public static long start() { return ENABLED && THREADS.isCurrentThreadCpuTimeSupported()?THREADS.getCurrentThreadCpuTime():-1; }
    public static long elapsed(long start) { return start<0?-1:Math.max(0,THREADS.getCurrentThreadCpuTime()-start); }
    public static void terrain(long start) { long elapsed=elapsed(start);if(elapsed>=0){TERRAIN_CPU.add(elapsed);TERRAIN_JOBS.increment();} }
    public static void feature(long start) { featureCpu=elapsed(start); }
    public static long terrainCpu() { return TERRAIN_CPU.sum(); }
    public static long terrainJobs() { return TERRAIN_JOBS.sum(); }
    public static long featureCpu() { return featureCpu; }
}
