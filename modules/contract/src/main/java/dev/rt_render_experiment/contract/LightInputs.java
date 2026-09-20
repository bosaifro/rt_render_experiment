package dev.rt_render_experiment.contract;

import java.util.List;
import java.util.Objects;


public final class LightInputs {
    public static final int API_VERSION = 2;
    private LightInputs() {}
    public enum Stratum { WORLD, OBJECT, CAMERA_ATTACHED }







    public record Emission(int sourceDefinition,float strength,SceneInputs.Vec3 measuredChroma) {
        public Emission {
            Objects.requireNonNull(measuredChroma);
            if (sourceDefinition<0 || sourceDefinition>=50 || !Float.isFinite(strength) || strength<0
                || measuredChroma.x()<0 || measuredChroma.y()<0 || measuredChroma.z()<0) throw new IllegalArgumentException("Invalid source emission");
        }
        public static Emission areaImportance(SceneInputs.Vec3 radiance) {
            var result=new Emission(0,1,radiance);
            if(radiance.x()==0 && radiance.y()==0 && radiance.z()==0)throw new IllegalArgumentException("An explicit area summary requires positive support");
            return result;
        }
    }
    public sealed interface Source permits Point,Area {
        SceneInputs.Key key(); long revision(); Emission emission(); SceneInputs.Origin samplingOrigin(); long medium(); Stratum stratum();
    }
    public record Point(SceneInputs.Key key,long revision,Emission emission,SceneInputs.Origin samplingOrigin,long medium,
                        Stratum stratum,SceneInputs.Origin position,float radius) implements Source {
        public Point {
            validate(key,revision,emission,samplingOrigin,medium,stratum); Objects.requireNonNull(position);
            if (!Float.isFinite(radius) || radius<=0) throw new IllegalArgumentException("Invalid point-proxy radius");
        }
    }

    public record Area(SceneInputs.Key key,long revision,Emission emission,SceneInputs.Origin samplingOrigin,long medium,
                       Stratum stratum,SceneInputs.Key geometry,SceneInputs.Key part,long ordinal) implements Source {
        public Area {
            validate(key,revision,emission,samplingOrigin,medium,stratum); Objects.requireNonNull(geometry); Objects.requireNonNull(part);
            if (ordinal<0) throw new IllegalArgumentException("Invalid area primitive identity");
        }
    }
    public record Publication(long revision,List<Source> sources) {
        public Publication { if (revision<=0) throw new IllegalArgumentException("Invalid source publication"); sources=List.copyOf(sources); }
        public static Publication empty() { return new Publication(1,List.of()); }
    }
    private static void validate(SceneInputs.Key key,long revision,Emission emission,SceneInputs.Origin samplingOrigin,long medium,Stratum stratum) {
        Objects.requireNonNull(key); Objects.requireNonNull(emission); Objects.requireNonNull(samplingOrigin); Objects.requireNonNull(stratum);
        if (revision<=0 || medium<0) throw new IllegalArgumentException("Invalid source identity/revision/medium");
    }
}
