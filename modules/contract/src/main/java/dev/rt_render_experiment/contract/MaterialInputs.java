package dev.rt_render_experiment.contract;

import java.util.Objects;


public final class MaterialInputs {
    public static final int API_VERSION = 3;
    private MaterialInputs() {}


    public enum Emission { NONE, MERGED, CALIBRATED }
    public enum Sampling { CRISP, TEXTURE_SAMPLER }
    public record Layers(SceneInputs.Key coverage, boolean coverageUsesTint, SceneInputs.Key overlay,
                         Emission emission, SceneInputs.Vec3 calibratedRadiance,
                         boolean modelResponse, boolean extendedRayClearance, Sampling sampling,
                         TextureInputs.Encoding tintEncoding,TextureInputs.Encoding emissionTintEncoding) {
        public Layers(SceneInputs.Key coverage,boolean coverageUsesTint,SceneInputs.Key overlay,Emission emission,SceneInputs.Vec3 calibratedRadiance,
                      boolean modelResponse,boolean extendedRayClearance,Sampling sampling) {
            this(coverage,coverageUsesTint,overlay,emission,calibratedRadiance,modelResponse,extendedRayClearance,sampling,TextureInputs.Encoding.LINEAR,TextureInputs.Encoding.LINEAR);
        }
        public Layers {
            Objects.requireNonNull(coverage); Objects.requireNonNull(overlay);
            Objects.requireNonNull(emission); Objects.requireNonNull(calibratedRadiance); Objects.requireNonNull(sampling);
            Objects.requireNonNull(tintEncoding);Objects.requireNonNull(emissionTintEncoding);
            if (calibratedRadiance.x()<0 || calibratedRadiance.y()<0 || calibratedRadiance.z()<0)
                throw new IllegalArgumentException("Negative emission radiance");
            if (emission!=Emission.CALIBRATED && !calibratedRadiance.equals(new SceneInputs.Vec3(0,0,0)))
                throw new IllegalArgumentException("Unused calibrated emission input");
        }
        public static Layers plain(SceneInputs.Key color) {
            return new Layers(color,true,new SceneInputs.Key(0,0),Emission.NONE,new SceneInputs.Vec3(0,0,0),false,false,Sampling.CRISP);
        }
    }
}
