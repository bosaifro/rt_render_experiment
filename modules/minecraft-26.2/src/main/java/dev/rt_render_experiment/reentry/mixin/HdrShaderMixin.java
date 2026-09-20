package dev.rt_render_experiment.reentry.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import dev.rt_render_experiment.reentry.HdrPipelines;
import net.minecraft.client.renderer.ShaderDefines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=VulkanDevice.class,remap=false)
public abstract class HdrShaderMixin {
    @WrapOperation(method="compileShader",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;injectDefines(Ljava/lang/String;Lnet/minecraft/client/renderer/ShaderDefines;)Ljava/lang/String;"),require=1,allow=1)
    private String rt_render_experiment$linear(String source,ShaderDefines defines,Operation<String> original) {

        if(defines.flags().contains(HdrPipelines.LINEAR) && HdrPipelines.hasColorOutput(source))source=HdrPipelines.linearize(source);
        return original.call(source,defines);
    }
}
