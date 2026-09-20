package dev.rt_render_experiment.reentry.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.rt_render_experiment.reentry.FeatureProducer;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value=MovingBlockFeatureRenderer.class,remap=false)
public abstract class MovingMaterialMixin {
    @WrapOperation(method="buildGroup",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"),require=1,allow=1)
    private void rt_render_experiment$block(ModelBlockRenderer renderer,BlockQuadOutput output,float x,float y,float z,BlockAndTintGetter region,BlockPos pos,BlockState state,BlockStateModel model,long seed,Operation<Void> original) {
        String previous=FeatureProducer.block(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        try { original.call(renderer,output,x,y,z,region,pos,state,model,seed); }finally { FeatureProducer.block(previous); }
    }
}
