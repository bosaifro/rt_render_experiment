package dev.rt_render_experiment.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;


public final class MediumInputs {
    public static final int API_VERSION=3;
    private MediumInputs() {}
    public enum Kind {
        WATER(MediumEncoding.WATER),GLASS(MediumEncoding.GLASS),ICE(MediumEncoding.ICE);
        private final int encoding;
        Kind(int encoding) { this.encoding=encoding; }
        public int encoding() { return encoding; }
    }

    public record Definition(Kind kind,float ior,SceneInputs.Vec3 absorption,SceneInputs.Vec3 scattering) {
        public Definition {
            Objects.requireNonNull(kind);Objects.requireNonNull(absorption);Objects.requireNonNull(scattering);
            if(!Float.isFinite(ior) || ior<1 || ior>2.5f)throw new IllegalArgumentException("IOR outside RtRenderExperiment's supported material domain");
            float[] a={absorption.x(),absorption.y(),absorption.z()},s={scattering.x(),scattering.y(),scattering.z()};
            for(int i=0;i<3;i++)if(a[i]<0 || s[i]<0 || !Float.isFinite(a[i]+s[i]))
                throw new IllegalArgumentException("Invalid medium extinction");
        }
    }
    public record Enclosure(long volume,Definition medium) {
        public Enclosure { if(volume<=0)throw new IllegalArgumentException("Missing volume identity");Objects.requireNonNull(medium); }
    }

    public record Volume(long identity,Definition medium) {
        public Volume { if(identity<=0)throw new IllegalArgumentException("Missing volume identity");Objects.requireNonNull(medium); }
    }






    public record Domain(List<Volume> volumes) {
        public Domain {
            volumes=volumes.stream().sorted(java.util.Comparator.comparingLong(Volume::identity)).toList();
            var ids=new HashSet<Long>();for(var volume:volumes)if(!ids.add(volume.identity()))throw new IllegalArgumentException("Duplicate medium definition");
        }
    }








    public record Origin(long world,long sceneRevision,SceneInputs.Origin position,float clearance,List<Enclosure> enclosures) {
        public Origin {
            Objects.requireNonNull(position);enclosures=List.copyOf(enclosures);
            if(world<=0 || sceneRevision<=0 || !(clearance>0) || !Float.isFinite(clearance) || enclosures.size()>MediumEncoding.CAPACITY)
                throw new IllegalArgumentException("Invalid initial-medium domain or capacity");
            var ids=new HashSet<Long>();for(var enclosure:enclosures)if(!ids.add(enclosure.volume()))throw new IllegalArgumentException("Repeated initial volume identity");
        }
        public boolean inWater() { return !enclosures.isEmpty() && enclosures.getLast().medium().kind()==Kind.WATER; }
    }
}
