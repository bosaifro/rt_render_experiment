package dev.rt_render_experiment.reentry.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import java.util.Collection;
import dev.rt_render_experiment.reentry.RtRenderExperimentFrameGraph;
import dev.rt_render_experiment.reentry.TerrainProducer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=ChunkSectionsToRender.class,remap=false)
public abstract class TerrainDrawReceiptMixin {
    @WrapOperation(method="renderGroup",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderPass;drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V"),require=1,allow=1)
    private <T> void rt_render_experiment$receipt(RenderPass pass,Collection<RenderPass.Draw<T>> draws,GpuBuffer indices,IndexType type,Collection<String> uniforms,T argument,Operation<Void> original) {
        var scope=RtRenderExperimentFrameGraph.active();
        var retained=scope==null?draws:draws.stream().filter(draw->!scope.replace(new TerrainProducer.Range(draw.vertexBuffer(),(long)draw.baseVertex()*28,draw.indexCount()))).toList();
        if(!retained.isEmpty())original.call(pass,retained,indices,type,uniforms,argument);
    }
}
