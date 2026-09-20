package dev.rt_render_experiment.reentry;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RtRenderExperimentClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("rt_render_experiment");
    @Override public void onInitializeClient() {
        LOGGER.info("RT_RENDER_EXPERIMENT_REENTRY target=26.2 host=original seam=LevelRenderer.render/FrameGraphBuilder.execute producerApi={}",
            dev.rt_render_experiment.contract.ProducerResources.API_VERSION);
    }
}
