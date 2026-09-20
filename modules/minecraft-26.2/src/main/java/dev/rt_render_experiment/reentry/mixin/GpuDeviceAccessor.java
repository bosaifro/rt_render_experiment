package dev.rt_render_experiment.reentry.mixin;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=GpuDevice.class,remap=false)
public interface GpuDeviceAccessor { @Accessor("backend") GpuDeviceBackend rt_render_experiment$backend(); }
