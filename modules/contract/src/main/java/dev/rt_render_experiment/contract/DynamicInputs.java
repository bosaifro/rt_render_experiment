package dev.rt_render_experiment.contract;

import java.util.List;
import java.util.Objects;


public final class DynamicInputs {
    private DynamicInputs() {}
    public record PreparedObject(SceneInputs.Key key,SceneInputs.Origin origin,SceneInputs.Transform transform,SceneInputs.Motion motion,
                         Participation participation,long topologyRevision,long appearanceRevision,List<SceneInputs.Primitive> primitives,
                         List<SceneInputs.OpticalModel> opticalModels) {
        public PreparedObject(SceneInputs.Key key,SceneInputs.Origin origin,SceneInputs.Transform transform,SceneInputs.Motion motion,
                              Participation participation,long topologyRevision,long appearanceRevision,List<SceneInputs.Primitive> primitives) {
            this(key,origin,transform,motion,participation,topologyRevision,appearanceRevision,primitives,List.of());
        }
        public PreparedObject {
            Objects.requireNonNull(key);Objects.requireNonNull(origin);Objects.requireNonNull(transform);Objects.requireNonNull(motion);
            Objects.requireNonNull(participation);primitives=List.copyOf(primitives);opticalModels=List.copyOf(opticalModels);
            if(topologyRevision<=0 || appearanceRevision<=0)throw new IllegalArgumentException("Invalid prepared object revision");
        }
    }
}
