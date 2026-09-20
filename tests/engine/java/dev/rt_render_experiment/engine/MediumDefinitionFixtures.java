package dev.rt_render_experiment.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.SceneInputs;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VK12;


public final class MediumDefinitionFixtures {
    private MediumDefinitionFixtures() {}
    static MediumInputs.Domain domain(boolean changed) {
        var base=GlassFixtures.glass().getFirst();var medium=changed
            ?new MediumInputs.Definition(MediumInputs.Kind.GLASS,1.5f,new SceneInputs.Vec3(.7f,.3f,.1f),new SceneInputs.Vec3(0,0,0)):base.medium();
        return new MediumInputs.Domain(List.of(new MediumInputs.Volume(base.volume(),medium)));
    }
    static SceneStore.Revision scene() {
        var source=OrientedMediumFixtures.scene(false,0);var meshes=List.of(source.geometry().get(0),source.geometry().get(2));
        return new SceneStore.Revision(1,1,meshes,meshes.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
    static RenderFrame outside() {
        var base=InitialMediaFixtures.frame();
        return RenderFrame.fromCamera(16,16,0,base.origin(),CameraFixtures.rows(new Matrix4f()),
            CameraFixtures.rows(new Matrix4f().perspective((float)Math.PI/2,1,.1f,100,true)),base.environment(),RenderFrame.DepthConvention.FORWARD)
            .withReconstruction(RenderFrame.Reconstruction.PORTABLE).withWorldLightReuse(true);
    }
    private static dev.rt_render_experiment.vulkan.VulkanResources.Buffer copyDefinition(FixtureHost host,org.lwjgl.vulkan.VkCommandBuffer command,dev.rt_render_experiment.vulkan.VulkanResources.Buffer source) {
        source.markUsed();
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            dev.rt_render_experiment.vulkan.VulkanBarriers.record(command,stack,new dev.rt_render_experiment.vulkan.VulkanBarriers.Scope(
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_READ_BIT));
        }
        return host.copy(command,source);
    }
    private static HostExecution.ImageGrant grant(ResourceViews.Image image,long serial) { return new HostExecution.ImageGrant(image,1,Set.of(HostExecution.Access.ATTACHMENT_WRITE),serial); }
    private static HostExecution.Window window(long serial,long id,HostExecution.Stage stage,HostExecution.ImageGrant... images) { return new HostExecution.Window(1,id,serial,stage,List.of(),List.of(images)); }}
