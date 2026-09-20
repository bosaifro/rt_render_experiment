package dev.rt_render_experiment.reentry.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.rt_render_experiment.reentry.TerrainProducer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=LevelRenderer.class,remap=false)
public abstract class TerrainDrawCaptureMixin {
    @Inject(method="prepareChunkRenders",at=@At("HEAD"))
    private void rt_render_experiment$begin(CallbackInfoReturnable<ChunkSectionsToRender> callback) { TerrainProducer.beginDraws(); }
    @WrapOperation(method="prepareChunkRenders",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;getRenderSectionSlice(Lnet/minecraft/client/renderer/chunk/SectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSectionBufferSlice;"),require=1,allow=1)
    private SectionRenderDispatcher.RenderSectionBufferSlice rt_render_experiment$draw(SectionRenderDispatcher dispatcher,SectionMesh mesh,ChunkSectionLayer layer,Operation<SectionRenderDispatcher.RenderSectionBufferSlice> original) {
        var slice=original.call(dispatcher,mesh,layer);TerrainProducer.captureDraw(dispatcher,mesh,layer,slice);return slice;
    }
}
