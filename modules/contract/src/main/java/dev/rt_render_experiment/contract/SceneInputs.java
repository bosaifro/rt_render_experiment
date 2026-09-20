package dev.rt_render_experiment.contract;

import java.util.List;
import java.util.Objects;


public final class SceneInputs {
    public static final int API_VERSION = 14;
    private SceneInputs() {}


    public record Key(long high, long low) implements Comparable<Key> {
        @Override public int compareTo(Key other) {
            int order = Long.compareUnsigned(high, other.high);
            return order != 0 ? order : Long.compareUnsigned(low, other.low);
        }
    }
    public record Revision(long world, long topology, long deformation, long placement,
                           long appearance, long resources, long coverage) {
        public Revision {
            if (world <= 0 || topology <= 0 || deformation < 0 || placement < 0
                || appearance <= 0 || resources <= 0 || coverage <= 0) throw new IllegalArgumentException("Invalid content revision");
        }
    }








    public record RelatedSources(java.util.Map<Key,Revision> required) {
        public RelatedSources {
            required=java.util.Map.copyOf(required);
            if(required.isEmpty())throw new IllegalArgumentException("Empty source relation");
            long world=required.values().iterator().next().world();
            if(required.values().stream().anyMatch(value->value.world()!=world))throw new IllegalArgumentException("Related sources cross world epochs");
        }
    }








    public record SourceAbsence(long world,long frame,Key source,java.util.Set<Key> parts,java.util.Set<Key> pointSources) {
        public SourceAbsence {
            Objects.requireNonNull(source);parts=java.util.Set.copyOf(parts);pointSources=java.util.Set.copyOf(pointSources);
            if(world<=0 || frame<=0 || parts.isEmpty() && pointSources.isEmpty())throw new IllegalArgumentException("Invalid current source absence");
        }
    }







    public record SourcePatch(long world,long frame,Key source,java.util.Set<Key> parts,java.util.Set<Key> pointSources,PreparedSource replacement) {
        public SourcePatch {
            Objects.requireNonNull(source);parts=java.util.Set.copyOf(parts);pointSources=java.util.Set.copyOf(pointSources);Objects.requireNonNull(replacement);
            var content=replacement.content();
            if(world<=0 || frame<=0 || parts.isEmpty() || content.status()!=PreparationStatus.READY || content.geometry().size()!=1
                || content.request().expected().world()!=world || content.request().source().equals(source))throw new IllegalArgumentException("Invalid current source patch");
            var geometry=content.geometry().getFirst();
            if(!geometry.key().equals(content.request().source()) || !geometry.revision().equals(content.request().expected()))throw new IllegalArgumentException("Patch source identity disagrees");
            for(var p:geometry.primitives())if(!parts.contains(p.part()))throw new IllegalArgumentException("Patch writes an undeclared part");
            for(var f:geometry.fluids())if(!parts.contains(f.part()))throw new IllegalArgumentException("Patch writes an undeclared fluid part");
            for(var m:geometry.opticalModels())if(!parts.contains(m.part()))throw new IllegalArgumentException("Patch writes an undeclared optical model");
            for(var light:replacement.lights()) {
                if(light instanceof LightInputs.Point && !pointSources.contains(light.key()))throw new IllegalArgumentException("Patch writes an undeclared point source");
                if(light instanceof LightInputs.Area area && (!area.geometry().equals(geometry.key()) || !parts.contains(area.part())))throw new IllegalArgumentException("Patch area lacks its source association");
            }
        }
    }
    public record Vec3(float x, float y, float z) {
        public Vec3 { finite(x); finite(y); finite(z); }
    }
    public record Vec2(float x,float y) {
        public Vec2 { finite(x); finite(y); }
    }

    public record Origin(double x, double y, double z) {
        public Origin {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Invalid origin");
        }
    }
    public record Color(float r, float g, float b, float a) {
        public Color { unit(r); unit(g); unit(b); unit(a); }
    }
    public record Corner(Vec3 position, float u, float v, Color tint, Vec3 normal, Vec3 tangent,
                         Vec2 emissionUv, Color emissionTint, Vec2 overlayTexel) {
        public Corner(Vec3 position,float u,float v,Color tint,Vec3 normal,Vec3 tangent) {
            this(position,u,v,tint,normal,tangent,new Vec2(u,v),tint,new Vec2(0,0));
        }
        public Corner {
            Objects.requireNonNull(position); Objects.requireNonNull(tint);
            Objects.requireNonNull(normal); Objects.requireNonNull(tangent); finite(u); finite(v);
            Objects.requireNonNull(emissionUv); Objects.requireNonNull(emissionTint); Objects.requireNonNull(overlayTexel);
        }
    }

    public enum Coverage { OPAQUE, CUTOUT, FILTER, DIELECTRIC }





    public enum HostOcclusion { PASS, BLOCK }
    public enum Motion { STATIC, RIGID, DEFORMING }








    public enum Boundary { UNQUALIFIED, NESTED_VOLUME, OPAQUE_CONTACT }

    public record Surface(Key key, int material, int properties, Key colorResource, Key emissionResource,
                          Coverage coverage, float cutoff, boolean doubleSided, long medium,
                          int layer, float layerSeparation, MaterialInputs.Layers layers, HostOcclusion hostOcclusion, Boundary boundary) {
        public Surface(Key key,int material,int properties,Key colorResource,Key emissionResource,Coverage coverage,
                       float cutoff,boolean doubleSided,long medium,int layer,float layerSeparation,MaterialInputs.Layers layers,HostOcclusion hostOcclusion) {
            this(key,material,properties,colorResource,emissionResource,coverage,cutoff,doubleSided,medium,layer,layerSeparation,layers,hostOcclusion,Boundary.UNQUALIFIED);
        }
        public Surface withNestedVolume(long identity) {
            return new Surface(key,material,properties,colorResource,emissionResource,coverage,cutoff,doubleSided,identity,
                layer,layerSeparation,layers,hostOcclusion,Boundary.NESTED_VOLUME);
        }






        public Surface withOpaqueContact(long identity) {
            return new Surface(key,material,properties,colorResource,emissionResource,coverage,cutoff,doubleSided,identity,
                layer,layerSeparation,layers,hostOcclusion,Boundary.OPAQUE_CONTACT);
        }
        public Surface {
            Objects.requireNonNull(key); Objects.requireNonNull(colorResource); Objects.requireNonNull(emissionResource);
            Objects.requireNonNull(coverage); Objects.requireNonNull(hostOcclusion); Objects.requireNonNull(layers); unit(cutoff); finite(layerSeparation);
            if (material < 0 || medium < 0 || layer < 0 || layerSeparation < 0) throw new IllegalArgumentException("Invalid surface");
            Objects.requireNonNull(boundary);
            if(boundary==Boundary.NESTED_VOLUME && (medium==0 || coverage!=Coverage.DIELECTRIC || !doubleSided
                || layer!=0 || layerSeparation!=0 || (properties&(SurfaceProperties.THIN|SurfaceProperties.TRANSLUCENT))!=0))
                throw new IllegalArgumentException("Oriented volume requires an identified two-sided dielectric boundary without sheet semantics or layer offsets");
            if(boundary==Boundary.OPAQUE_CONTACT && (medium==0 || coverage!=Coverage.OPAQUE || layer!=0 || layerSeparation!=0
                || (properties&(SurfaceProperties.THIN|SurfaceProperties.TRANSLUCENT))!=0))
                throw new IllegalArgumentException("Opaque contact requires an identified opaque surface without sheet semantics or offsets");
            if (layers.emission()==MaterialInputs.Emission.NONE && !emissionResource.equals(new Key(0,0)))
                throw new IllegalArgumentException("Emission texture without an emission consumer");
        }
    }
    public record Primitive(Key part, long ordinal, Surface surface, List<Corner> corners) {
        public Primitive {
            Objects.requireNonNull(part); Objects.requireNonNull(surface); corners = List.copyOf(corners);
            if (ordinal < 0 || (corners.size() != 3 && corners.size() != 4)) throw new IllegalArgumentException("Expected prepared triangle or quad");
        }
    }







    public record OpticalModel(Key part,List<Primitive> surfaces,java.util.Set<Long> occludedOrdinals) {
        public OpticalModel(Key part,List<Primitive> surfaces) { this(part,surfaces,java.util.Set.of()); }
        public OpticalModel {
            Objects.requireNonNull(part);surfaces=List.copyOf(surfaces);occludedOrdinals=java.util.Set.copyOf(occludedOrdinals);
            if(surfaces.isEmpty())throw new IllegalArgumentException("Empty optical model");
            var ordinals=new java.util.HashSet<Long>();
            for(var primitive:surfaces)if(!part.equals(primitive.part()) || !ordinals.add(primitive.ordinal())
                || primitive.surface().coverage()!=Coverage.DIELECTRIC)
                throw new IllegalArgumentException("Optical model has conflicting identity or non-optical coverage");
            if(!ordinals.containsAll(occludedOrdinals))throw new IllegalArgumentException("Occlusion fact lacks its prepared surface");
        }
    }

    public static final class Transform {
        private final float[] rows;
        public Transform(float[] rows) {
            if (rows.length != 12) throw new IllegalArgumentException("Affine transform requires 12 values");
            this.rows = rows.clone(); for (float value : this.rows) finite(value);
            if (Math.abs(determinant()) < 1e-12f) throw new IllegalArgumentException("Singular transform");
        }
        public static Transform identity() { return new Transform(new float[]{1,0,0,0, 0,1,0,0, 0,0,1,0}); }
        public float get(int row, int column) { return rows[Math.addExact(Math.multiplyExact(row,4),column)]; }
        public float[] rows() { return rows.clone(); }
        public float determinant() {
            return rows[0]*(rows[5]*rows[10]-rows[6]*rows[9])
                - rows[1]*(rows[4]*rows[10]-rows[6]*rows[8]) + rows[2]*(rows[4]*rows[9]-rows[5]*rows[8]);
        }
    }
    public record Geometry(Key key, Revision revision, Origin origin, Transform current, Transform previous,
                           boolean previousValid, Motion motion, Participation participation, List<Primitive> primitives,List<FluidInputs.Cell> fluids,
                           List<OpticalModel> opticalModels) {
        public Geometry(Key key,Revision revision,Origin origin,Transform current,Transform previous,boolean previousValid,
                        Motion motion,Participation participation,List<Primitive> primitives,List<FluidInputs.Cell> fluids) {
            this(key,revision,origin,current,previous,previousValid,motion,participation,primitives,fluids,List.of());
        }
        public Geometry(Key key,Revision revision,Origin origin,Transform current,Transform previous,boolean previousValid,
                        Motion motion,Participation participation,List<Primitive> primitives) {
            this(key,revision,origin,current,previous,previousValid,motion,participation,primitives,List.of(),List.of());
        }
        public Geometry {
            Objects.requireNonNull(key); Objects.requireNonNull(revision); Objects.requireNonNull(origin);
            Objects.requireNonNull(current); Objects.requireNonNull(previous); Objects.requireNonNull(motion);
            Objects.requireNonNull(participation); primitives = List.copyOf(primitives); fluids=List.copyOf(fluids);opticalModels=List.copyOf(opticalModels);
            if(!opticalModels.isEmpty()) {
            var parts=new java.util.HashSet<Key>();
            for(var model:opticalModels)if(!parts.add(model.part()))throw new IllegalArgumentException("Duplicate complete optical model");
            var visible=new java.util.HashMap<Key,java.util.Map<Long,Primitive>>();
            for(var primitive:primitives)if(parts.contains(primitive.part()))
                visible.computeIfAbsent(primitive.part(),ignored->new java.util.HashMap<>()).put(primitive.ordinal(),primitive);
            for(var model:opticalModels) {
                var records=visible.getOrDefault(model.part(),java.util.Map.of());
                var prepared=new java.util.HashSet<Long>();
                for(var primitive:model.surfaces()) {
                    prepared.add(primitive.ordinal());
                    var original=records.get(primitive.ordinal());
                    if(model.occludedOrdinals().contains(primitive.ordinal())?original!=null:!primitive.equals(original))
                        throw new IllegalArgumentException("Optical visibility or primitive identity disagrees with rendered facts");
                }
                for(var primitive:records.values())if(primitive.surface().coverage()==Coverage.DIELECTRIC && !prepared.contains(primitive.ordinal()))
                    throw new IllegalArgumentException("Complete optical model omits a visible optical surface");
            }
            }
        }
    }

    public record PreparationRequest(long request, Key source, Revision expected, long maximumBytes) {
        public PreparationRequest {
            Objects.requireNonNull(source); Objects.requireNonNull(expected);
            if (request <= 0 || maximumBytes <= 0) throw new IllegalArgumentException("Invalid preparation request");
        }
    }
    public enum PreparationStatus { READY, UNAVAILABLE, UNSUPPORTED, FAILED }
    public record PreparationResult(PreparationRequest request, PreparationStatus status, List<Geometry> geometry,
                                    long preparedBytes, String reason) {
        public PreparationResult {
            Objects.requireNonNull(request); Objects.requireNonNull(status); geometry = List.copyOf(geometry); Objects.requireNonNull(reason);
            if (preparedBytes < 0 || preparedBytes > request.maximumBytes() || reason.isBlank()
                || (status != PreparationStatus.READY && !geometry.isEmpty())) throw new IllegalArgumentException("Invalid preparation result");
            for (Geometry item : geometry) if (item.revision().world() != request.expected().world()
                || item.revision().resources() != request.expected().resources()) throw new IllegalArgumentException("Mixed preparation epoch");
        }
    }






    public record PreparedSource(PreparationResult content,List<TextureInputs.Texture> textures,List<LightInputs.Source> lights) {
        public PreparedSource {
            Objects.requireNonNull(content);textures=List.copyOf(textures);lights=List.copyOf(lights);
            if(content.status()!=PreparationStatus.READY && (!textures.isEmpty() || !lights.isEmpty()))
                throw new IllegalArgumentException("Failed preparation retained content");
        }
        public static PreparedSource geometryOnly(PreparationResult content) { return new PreparedSource(content,List.of(),List.of()); }
    }
    static void finite(float v) { if (!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite content value"); }
    static void unit(float v) { if (!(v >= 0 && v <= 1)) throw new IllegalArgumentException("Expected unit interval"); }
}
