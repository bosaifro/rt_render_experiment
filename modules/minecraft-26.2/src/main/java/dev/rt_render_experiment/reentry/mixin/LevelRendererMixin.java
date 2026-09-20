package dev.rt_render_experiment.reentry.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import dev.rt_render_experiment.reentry.RtRenderExperimentFrameGraph;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = LevelRenderer.class, remap = false)
public abstract class LevelRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V"), require = 1, allow = 1)
    private void rt_render_experiment$execute(FrameGraphBuilder graph, GraphicsResourceAllocator allocator, FrameGraphBuilder.Inspector inspector,
                              Operation<Void> original,GraphicsResourceAllocator sourceAllocator,DeltaTracker delta,boolean renderOutline,
                              CameraRenderState camera,Matrix4fc view,GpuBufferSlice fog,Vector4f fogColor,boolean renderSky) {
        try (var execution = RtRenderExperimentFrameGraph.open((LevelRenderer)(Object)this, graph,delta.getGameTimeDeltaPartialTick(false),renderSky)) {
            original.call(graph, allocator, execution.inspect(inspector));
            execution.finish();
        }
    }
}
