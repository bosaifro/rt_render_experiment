package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.contract.SceneInputs;
import org.lwjgl.vulkan.VkCommandBuffer;


public interface R2Scene {
    long serial();
    String shaderIdentity();
    SceneInputs.Origin origin();
    long recordsAddress();
    long sourcesAddress();

    int sourceCount();
    long materialsAddress();
    long atmosphereAddress();
    long accelerationStructure();
    int filterPrimitives();
    void requireInitialMedia(RenderFrame frame);
    WorldLightDomain worldLightDomain();
    int cloudSlot(RenderFrame.Cloud cloud);
    void bindTextures(VkCommandBuffer command, int bindPoint, long layout);
    void markUsed();

    default boolean represents(SceneInputs.Geometry input) { return false; }
}
