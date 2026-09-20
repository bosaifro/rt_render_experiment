package dev.rt_render_experiment.reentry;
import java.util.function.Function;
import net.minecraft.client.renderer.LevelRenderer;

public final class ProducerFeeds {
    private static Function<LevelRenderer,ProducerFeed> provider;
    private ProducerFeeds() {}
    public static void install(Function<LevelRenderer,ProducerFeed> replacement) {
        if(provider!=null)throw new IllegalStateException("Conflicting terrain producers");provider=java.util.Objects.requireNonNull(replacement);
    }
    public static ProducerFeed current(LevelRenderer renderer) {
        if(provider!=null)return provider.apply(renderer);
        return renderer.sectionRenderDispatcher()==null?null:TerrainProducer.state(renderer.sectionRenderDispatcher());
    }
}
