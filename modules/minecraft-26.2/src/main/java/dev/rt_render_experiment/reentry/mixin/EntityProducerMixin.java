package dev.rt_render_experiment.reentry.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.rt_render_experiment.reentry.PhysicalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=EntityRenderDispatcher.class,remap=false)
public abstract class EntityProducerMixin {
    @Inject(method="extractEntity",at=@At("RETURN"))
    private void rt_render_experiment$identity(Entity entity,float partial,CallbackInfoReturnable<EntityRenderState> callback) {
        var state=(PhysicalPlayer.State)callback.getReturnValue();state.rt_render_experiment$entity(entity.getUUID());state.rt_render_experiment$physical(false);
    }
    @WrapMethod(method="submit")
    private void rt_render_experiment$physical(EntityRenderState state,CameraRenderState camera,double x,double y,double z,PoseStack pose,SubmitNodeCollector collector,Operation<Void> original) {
        original.call(state,camera,x,y,z,pose,((PhysicalPlayer.State)state).rt_render_experiment$physical()?PhysicalPlayer.modelsOnly(collector):collector);
    }
}
