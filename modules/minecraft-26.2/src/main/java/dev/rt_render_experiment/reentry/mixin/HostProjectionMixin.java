package dev.rt_render_experiment.reentry.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.rt_render_experiment.reentry.HostCamera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=GameRenderer.class,remap=false)
public abstract class HostProjectionMixin {
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),require=1,allow=1)
    private GpuBufferSlice rt_render_experiment$projection(ProjectionMatrixBuffer owner,Matrix4f projection,Operation<GpuBufferSlice> original) {
        var buffer=original.call(owner,projection);HostCamera.publish(projection);return buffer;
    }
}
