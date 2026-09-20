package dev.rt_render_experiment.reentry.mixin;
import dev.rt_render_experiment.reentry.PhysicalPlayer;
import dev.rt_render_experiment.reentry.VulkanAdmission;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=LevelExtractor.class,remap=false)
public abstract class EntityInterestMixin {
    @Inject(method="isEntityVisible",at=@At("RETURN"),cancellable=true)
    private void rt_render_experiment$interest(Entity entity,Frustum frustum,double x,double y,double z,CallbackInfoReturnable<Boolean> callback) {
        if(callback.getReturnValue() || !VulkanAdmission.captureEnabled())return;
        double radius=Minecraft.getInstance().options.getEffectiveRenderDistance()*16.0;
        if(!entity.isRemoved() && entity.shouldRender(x,y,z) && entity.distanceToSqr(x,y,z)<=radius*radius)callback.setReturnValue(true);
    }
    @Inject(method="extractVisibleEntities",at=@At("RETURN"))
    private void rt_render_experiment$player(Camera camera,Frustum frustum,DeltaTracker delta,LevelRenderState output,CallbackInfo callback) {
        if(!VulkanAdmission.captureEnabled() || camera.isDetached() || !Minecraft.getInstance().options.getCameraType().isFirstPerson()
            || !(camera.entity() instanceof LocalPlayer player) || player.isSpectator())return;
        if(output.entityRenderStates.stream().anyMatch(s->player.getUUID().equals(((PhysicalPlayer.State)s).rt_render_experiment$entity())))return;
        var state=Minecraft.getInstance().levelRenderer.entityRenderDispatcher().extractEntity(player,delta.getGameTimeDeltaPartialTick(false));
        ((PhysicalPlayer.State)state).rt_render_experiment$physical(true);output.entityRenderStates.add(state);output.lastEntityRenderStateCount=output.entityRenderStates.size();
    }
}
