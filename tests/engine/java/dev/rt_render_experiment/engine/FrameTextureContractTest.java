package dev.rt_render_experiment.engine;

import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FrameTextureContractTest {
    private static HostExecution.ImageGrant grant(long recording) {
        var image=new ResourceViews.Image(new ResourceViews.ResourceId(1),1,7,8,1,2,37,16,16,0,5,0,1,1,4,1);
        return new HostExecution.ImageGrant(image,2,Set.of(HostExecution.Access.SHADER_READ),recording);
    }
    private static TextureInputs.GpuTexture input(HostExecution.ImageGrant source,TextureInputs.GpuUse use) {
        return new TextureInputs.GpuTexture(new SceneInputs.Key(1,2),2,TextureInputs.Encoding.SRGB,
            TextureInputs.Sampling.linear(TextureInputs.Address.CLAMP),source,use);
    }
    private static HostExecution.Window window(long recording,HostExecution.Stage stage,HostExecution.ImageGrant... grants) {
        return new HostExecution.Window(1,1,recording,stage,List.of(),List.of(grants));
    }
    @Test void existingCallersStillRequestCopies() {
        var g=grant(3);
        var t=new TextureInputs.GpuTexture(new SceneInputs.Key(1,2),2,TextureInputs.Encoding.SRGB,TextureInputs.Address.CLAMP,g);
        assertEquals(TextureInputs.GpuUse.COPY,t.use());
        assertDoesNotThrow(()->t.requirePreparationWindow(window(2,HostExecution.Stage.SCENE_PREPARATION,g)));
    }
    @Test void borrowedTextureRequiresTheExactExplicitRecording() {
        var g=grant(3);var t=input(g,TextureInputs.GpuUse.FRAME_READ);
        assertDoesNotThrow(()->t.requirePreparationWindow(window(3,HostExecution.Stage.SCENE_PREPARATION,g)));
        assertThrows(IllegalArgumentException.class,()->t.requirePreparationWindow(window(2,HostExecution.Stage.SCENE_PREPARATION,g)));
        assertThrows(IllegalArgumentException.class,()->t.requirePreparationWindow(window(3,HostExecution.Stage.WORLD,g)));
        assertThrows(IllegalArgumentException.class,()->t.requirePreparationWindow(window(3,HostExecution.Stage.SCENE_PREPARATION)));
    }
    @Test void sameBytesAndHandlesDoNotRenewPermission() {
        var old=grant(3);var renewed=grant(4);var t=input(old,TextureInputs.GpuUse.FRAME_READ);
        assertEquals(old.view(),renewed.view());
        assertThrows(IllegalArgumentException.class,()->t.requirePreparationWindow(window(4,HostExecution.Stage.SCENE_PREPARATION,renewed)));
        assertDoesNotThrow(()->input(renewed,TextureInputs.GpuUse.FRAME_READ).requirePreparationWindow(window(4,HostExecution.Stage.SCENE_PREPARATION,renewed)));
    }
}
