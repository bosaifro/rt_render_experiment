package dev.rt_render_experiment.reentry;
import java.util.List;
import dev.rt_render_experiment.contract.*;
import dev.rt_render_experiment.engine.ProducerRegistry;

public interface ProducerFeed {
    ProducerRegistry registry();
    boolean closed();
    List<HostExecution.BufferGrant> drain(HostDevice host);
    LightInputs.Publication lights();
    String statistics();
    default ProducerResources.Geometry input(SceneInputs.Key key) { return registry().get(key); }
}
