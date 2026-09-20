package dev.rt_render_experiment.reentry.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.Map;
import dev.rt_render_experiment.reentry.QuadTint;
import dev.rt_render_experiment.reentry.SectionCapture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=SectionCompiler.class,remap=false)
public abstract class SectionCompilerMixin {
    @WrapMethod(method="compile")
    private SectionCompiler.Results rt_render_experiment$capture(SectionPos section,RenderSectionRegion region,VertexSorting sorting,SectionBufferBuilderPack builders,
                                                  Operation<SectionCompiler.Results> original) {
        if(!dev.rt_render_experiment.reentry.VulkanAdmission.captureEnabled())return original.call(section,region,sorting,builders);
        long cpu=dev.rt_render_experiment.reentry.ProducerCpu.start();
        try(var capture=new SectionCapture()) {
            var results=original.call(section,region,sorting,builders);
            try { capture.finish(results);return results; }
            catch(RuntimeException|Error failure) { results.release();throw failure; }
        } finally { dev.rt_render_experiment.reentry.ProducerCpu.terrain(cpu); }
    }
    @WrapOperation(method="compile",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),require=1,allow=1)
    private BlockState rt_render_experiment$block(RenderSectionRegion region,BlockPos pos,Operation<BlockState> original) {
        var state=original.call(region,pos);
        if(SectionCapture.enabled())SectionCapture.active().collector.beginBlock(state,SectionPos.sectionRelative(pos.getX()),SectionPos.sectionRelative(pos.getY()),SectionPos.sectionRelative(pos.getZ()));
        return state;
    }
    @WrapOperation(method="compile",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"),require=1,allow=1)
    private void rt_render_experiment$fluid(FluidRenderer renderer,BlockAndTintGetter region,BlockPos pos,FluidRenderer.Output output,BlockState block,FluidState fluid,Operation<Void> original) {
        if(SectionCapture.enabled())SectionCapture.active().collector.beginFluid(fluid);original.call(renderer,region,pos,output,block,fluid);
    }
    @Inject(method="lambda$compile$0",at=@At("RETURN"))
    private void rt_render_experiment$quad(Map<ChunkSectionLayer,BufferBuilder> layers,SectionBufferBuilderPack builders,float x,float y,float z,BakedQuad quad,QuadInstance instance,CallbackInfo callback) {
        if(SectionCapture.enabled())SectionCapture.active().collector.recordBlockQuad(quad.materialInfo().layer(),quad.materialInfo(),((QuadTint)instance).rt_render_experiment$albedoTint());
    }
    @Inject(method="lambda$compile$1",at=@At("RETURN"))
    private void rt_render_experiment$opaque(Map<ChunkSectionLayer,BufferBuilder> layers,SectionBufferBuilderPack builders,float x,float y,float z,BakedQuad quad,QuadInstance instance,CallbackInfo callback) {
        if(SectionCapture.enabled())SectionCapture.active().collector.recordBlockQuad(ChunkSectionLayer.SOLID,quad.materialInfo(),((QuadTint)instance).rt_render_experiment$albedoTint());
    }
    @Inject(method="lambda$compile$2",at=@At("RETURN"),cancellable=true)
    private void rt_render_experiment$fluidOutput(Map<ChunkSectionLayer,BufferBuilder> layers,SectionBufferBuilderPack builders,ChunkSectionLayer layer,CallbackInfoReturnable<VertexConsumer> callback) {
        if(SectionCapture.enabled())callback.setReturnValue(SectionCapture.active().collector.trackFluidQuads(layer,callback.getReturnValue()));
    }
}
