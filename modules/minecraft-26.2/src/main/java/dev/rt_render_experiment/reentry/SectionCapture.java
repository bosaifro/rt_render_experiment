package dev.rt_render_experiment.reentry;

import dev.rt_render_experiment.integration.minecraft.MinecraftSectionSemanticCollector;
import net.minecraft.client.renderer.chunk.SectionCompiler;


public final class SectionCapture implements AutoCloseable {
    private static final ThreadLocal<SectionCapture> ACTIVE=new ThreadLocal<>();
    private final SectionCapture previous=ACTIVE.get();
    public final MinecraftSectionSemanticCollector collector=new MinecraftSectionSemanticCollector();
    public SectionCapture() { ACTIVE.set(this); }
    public static boolean enabled() { return ACTIVE.get()!=null; }
    public static SectionCapture active() { return java.util.Objects.requireNonNull(ACTIVE.get(),"Missing section producer scope"); }
    public void finish(SectionCompiler.Results results) { ((SemanticMesh)(Object)results).rt_render_experiment$semantics(collector.build(results.renderedLayers)); }
    @Override public void close() { if(previous==null)ACTIVE.remove();else ACTIVE.set(previous); }
}
