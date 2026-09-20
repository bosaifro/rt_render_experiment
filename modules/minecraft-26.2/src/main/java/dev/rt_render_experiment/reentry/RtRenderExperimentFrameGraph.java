package dev.rt_render_experiment.reentry;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.reentry.mixin.FrameGraphAccessor;
import dev.rt_render_experiment.reentry.mixin.FramePassAccessor;
import dev.rt_render_experiment.reentry.mixin.FrameExternalAccessor;
import dev.rt_render_experiment.reentry.mixin.FrameInternalAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;


public final class RtRenderExperimentFrameGraph implements AutoCloseable {
    private record Task(FramePassAccessor pass, Runnable original) {}
    private final List<Task> tasks;
    private final List<Runnable> restore=new ArrayList<>();
    private final Map<TerrainProducer.Range,SceneInputs.Key> draws=TerrainProducer.draws();
    private WorldRenderer.Frame world;
    private boolean redirected;
    private static final ThreadLocal<RtRenderExperimentFrameGraph> ACTIVE=new ThreadLocal<>();
    private static String lastRefusal;
    private int replacedDraws,retainedDraws;
    private final long start = System.nanoTime();
    private int executed;
    private boolean closed;
    private static long frames;
    private static long producerFrames;
    private static long featureFrames;
    private boolean finished;
    public static long frames() { return frames; }
    public static long producerFrames() { return producerFrames; }
    public static long featureFrames() { return featureFrames; }
    public static RtRenderExperimentFrameGraph active() { return ACTIVE.get(); }
    private RtRenderExperimentFrameGraph(LevelRenderer renderer, FrameGraphBuilder graph,float partialTick,boolean renderSky) {
        java.util.Objects.requireNonNull(renderer);
        tasks = new ArrayList<>();
        for (Object pass : ((FrameGraphAccessor)graph).rt_render_experiment$passes()) {
            var access = (FramePassAccessor)pass;
            tasks.add(new Task(access,access.rt_render_experiment$task()));
        }
        if(ACTIVE.get()!=null)throw new IllegalStateException("Nested world graph interception");
        if(tasks.stream().filter(t->t.pass.rt_render_experiment$name().equals("clear")).count()!=1)throw new IllegalStateException("Host clear pass contract changed");
        try { world=WorldRenderer.begin(renderer,Minecraft.getInstance().gameRenderer.mainRenderTarget(),partialTick,renderSky); }
        catch(java.io.IOException|IllegalArgumentException|IllegalStateException failure) { refusal(failure); }
        if(world!=null) {
            for(Object resource:((FrameGraphAccessor)graph).rt_render_experiment$external()) {
                var external=(FrameExternalAccessor)resource;
                if(external.rt_render_experiment$resource()==world.original()) {
                    var original=external.rt_render_experiment$resource();restore.add(()->external.rt_render_experiment$resource(original));external.rt_render_experiment$resource(world.target());
                }
            }
            for(Object resource:((FrameGraphAccessor)graph).rt_render_experiment$internal()) {
                var internal=(FrameInternalAccessor)resource;
                if(internal.rt_render_experiment$descriptor() instanceof RenderTargetDescriptor descriptor && (descriptor.format()==GpuFormat.RGBA8_UNORM || descriptor.format()==GpuFormat.RGBA16_FLOAT)) {
                    restore.add(()->internal.rt_render_experiment$descriptor(descriptor));
                    internal.rt_render_experiment$descriptor(new RenderTargetDescriptor(descriptor.width(),descriptor.height(),descriptor.useDepth(),descriptor.clearColor(),GpuFormat.RGBA16_FLOAT));
                }
            }
            redirected=true;
            for(var task:tasks) {
                String name=task.pass.rt_render_experiment$name();
                if(name.equals("clear"))task.pass.rt_render_experiment$task(()->{
                    task.original.run();
                    try { world.world(); }
                    catch(HostCommands.RecordingFailure failure) {
                        if(!failure.canUseHostFallback())throw failure;
                        refusal(failure.getCause());restore();world.close();world=null;task.original.run();
                    }
                });
                else if(name.equals("sky"))task.pass.rt_render_experiment$task(()->{if(world==null || !world.accepted())task.original.run();});
                else if(name.equals("clouds"))task.pass.rt_render_experiment$task(()->{if(world==null || !world.accepted() || !world.cloudsOwned())task.original.run();});
            }
        }
        ACTIVE.set(this);
    }
    public static RtRenderExperimentFrameGraph open(LevelRenderer renderer, FrameGraphBuilder graph,float partialTick,boolean renderSky) { return new RtRenderExperimentFrameGraph(renderer,graph,partialTick,renderSky); }
    private static void refusal(Throwable failure) {
        String message=failure.toString();if(!message.equals(lastRefusal)) { lastRefusal=message;RtRenderExperimentClient.LOGGER.warn("RT_RENDER_EXPERIMENT_FRAME_REFUSED {}",message); }
    }
    public RenderTarget redirect(RenderTarget target) { return redirected && world!=null && target==world.original()?world.target():target; }
    public boolean replace(TerrainProducer.Range range) {
        var key=draws.get(range);boolean accepted=world!=null && key!=null && world.represents(key);
        if(accepted)replacedDraws++;else retainedDraws++;
        return accepted;
    }
    public boolean replaceFeature(net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo info) { return world!=null && world.represents(info); }
    public boolean represents(SceneInputs.Key key) {
        boolean accepted=world!=null && world.represents(key);if(accepted)replacedDraws++;else retainedDraws++;return accepted;
    }
    public void finish() { if(world!=null && world.accepted())world.display();finished=true; }
    private void restore() { redirected=false;for(var action:restore)action.run();restore.clear(); }
    public FrameGraphBuilder.Inspector inspect(FrameGraphBuilder.Inspector host) {
        return new FrameGraphBuilder.Inspector() {
            @Override public void acquireResource(String name) { host.acquireResource(name); }
            @Override public void releaseResource(String name) { host.releaseResource(name); }
            @Override public void beforeExecutePass(String name) { host.beforeExecutePass(name); }
            @Override public void afterExecutePass(String name) { executed++; host.afterExecutePass(name); }
        };
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        boolean rendered=finished && world!=null && world.accepted();
        if(rendered)producerFrames++;
        if(rendered && world.featureCount()>0)featureFrames++;
        ACTIVE.remove();restore();
        for (var task : tasks) task.pass.rt_render_experiment$task(task.original);
        if(world!=null)world.close();
        if (++frames == 1 || frames % 300 == 0) RtRenderExperimentClient.LOGGER.info("RT_RENDER_EXPERIMENT_GRAPH frame={} hostPasses={} graphCpuNs={} route={} replacedDraws={} retainedDraws={}",
            frames,executed,System.nanoTime()-start,rendered?"producer":"host",replacedDraws,retainedDraws);
    }
}
