package dev.rt_render_experiment.contract;

import java.util.List;
import java.util.Objects;


public final class ProducerResources {
    public static final int API_VERSION = 1;
    private ProducerResources() {}


    public enum Layout {
        BLOCK28(28), ENTITY36(36);
        private final int stride;
        Layout(int stride) { this.stride = stride; }
        public int stride() { return stride; }
    }
    public enum TintMode { PURE_FACT, UNLIT_VERTEX_COLOR }


    public enum Topology {
        QUADS_012_230(4, 2), TRIANGLES(3, 1);
        private final int vertices, triangles;
        Topology(int vertices, int triangles) { this.vertices = vertices; this.triangles = triangles; }
        public int verticesPerPrimitive() { return vertices; }
        public int trianglesPerPrimitive() { return triangles; }
    }


    public record Primitive(SceneInputs.Key part, long ordinal, SceneInputs.Surface surface, List<Integer> tints, boolean active) {
        public Primitive(SceneInputs.Key part,long ordinal,SceneInputs.Surface surface,int albedoTint) { this(part,ordinal,surface,List.of(albedoTint),true); }
        public Primitive(SceneInputs.Key part,long ordinal,SceneInputs.Surface surface,List<Integer> tints) { this(part,ordinal,surface,tints,true); }
        public Primitive {
            Objects.requireNonNull(part); Objects.requireNonNull(surface);
            tints=List.copyOf(tints);
            if(tints.size()!=1 && tints.size()!=3 && tints.size()!=4)throw new IllegalArgumentException("Incomplete pure tint factors");
            if (ordinal < 0) throw new IllegalArgumentException("Negative producer primitive ordinal");
        }
        public int albedoTint() { return tints.getFirst(); }
        public int tint(int corner) { return tints.size()==1?tints.getFirst():tints.get(corner); }
    }

    public static final class UniformPrimitives extends java.util.AbstractList<Primitive> implements java.util.RandomAccess {
        private final Primitive template;
        private final int count;
        public UniformPrimitives(Primitive template,int count) {
            this.template=Objects.requireNonNull(template);if(count<=0)throw new IllegalArgumentException("Empty uniform primitive table");
            Math.addExact(template.ordinal(),count-1L);this.count=count;
        }
        public Primitive template() { return template; }
        @Override public int size() { return count; }
        @Override public Primitive get(int index) {
            Objects.checkIndex(index,count);return new Primitive(template.part(),template.ordinal()+index,template.surface(),template.tints(),template.active());
        }
    }






    public record Geometry(SceneInputs.Key key, long world, long generation, long revision,
                           long transformRevision, long materialRevision, HostExecution.BufferGrant vertices,
                           Layout layout, Topology topology, TintMode tintMode, SceneInputs.Motion motion, int vertexCount, SceneInputs.Origin origin,
                           SceneInputs.Transform current, SceneInputs.Transform previous, Participation participation,
                           List<Primitive> primitives) {
        public Geometry(SceneInputs.Key key,long world,long generation,long revision,long transformRevision,long materialRevision,
                        HostExecution.BufferGrant vertices,Layout layout,Topology topology,int vertexCount,SceneInputs.Origin origin,
                        SceneInputs.Transform current,SceneInputs.Transform previous,Participation participation,List<Primitive> primitives) {
            this(key,world,generation,revision,transformRevision,materialRevision,vertices,layout,topology,TintMode.PURE_FACT,SceneInputs.Motion.STATIC,vertexCount,origin,current,previous,participation,primitives);
        }
        public Geometry(SceneInputs.Key key,long world,long generation,long revision,long transformRevision,long materialRevision,
                        HostExecution.BufferGrant vertices,Layout layout,Topology topology,TintMode tintMode,int vertexCount,SceneInputs.Origin origin,
                        SceneInputs.Transform current,SceneInputs.Transform previous,Participation participation,List<Primitive> primitives) {
            this(key,world,generation,revision,transformRevision,materialRevision,vertices,layout,topology,tintMode,SceneInputs.Motion.STATIC,vertexCount,origin,current,previous,participation,primitives);
        }
        public Geometry {
            Objects.requireNonNull(key); Objects.requireNonNull(vertices); Objects.requireNonNull(layout);
            Objects.requireNonNull(topology); Objects.requireNonNull(origin); Objects.requireNonNull(current);
            Objects.requireNonNull(previous); Objects.requireNonNull(participation);
            Objects.requireNonNull(tintMode);
            Objects.requireNonNull(motion);
            primitives = primitives instanceof UniformPrimitives?primitives:List.copyOf(primitives);
            if(tintMode==TintMode.UNLIT_VERTEX_COLOR && layout!=Layout.ENTITY36)
                throw new IllegalArgumentException("Packed terrain color contains baked lighting and cannot be admitted as pure tint");
            var metadata=primitives instanceof UniformPrimitives uniform?List.of(uniform.template()):primitives;
            for(var primitive:metadata)if(primitive.tints().size()!=1 && primitive.tints().size()!=topology.verticesPerPrimitive())
                throw new IllegalArgumentException("Tint factors disagree with primitive topology");
            if (world <= 0 || generation <= 0 || revision <= 0 || transformRevision <= 0 || materialRevision <= 0
                || vertexCount <= 0 || vertexCount % topology.verticesPerPrimitive() != 0
                || primitives.size() != vertexCount / topology.verticesPerPrimitive()
                || vertices.contentRevision() != revision)
                throw new IllegalArgumentException("Incomplete producer geometry publication");
            long bytes = Math.multiplyExact((long) vertexCount, layout.stride());
            if (vertices.view().length() != bytes || vertices.view().offset() % 4 != 0)
                throw new IllegalArgumentException("Producer range does not match its declared layout/count");
            if (!vertices.allowed().contains(HostExecution.Access.SHADER_READ)
                && !vertices.allowed().contains(HostExecution.Access.TRANSFER_READ))
                throw new IllegalArgumentException("Producer did not grant readable geometry");
            Math.multiplyExact(primitives.size(), topology.trianglesPerPrimitive());
        }
        public int triangleCount() { return Math.multiplyExact(primitives.size(), topology.trianglesPerPrimitive()); }
        public List<Primitive> metadata() { return primitives instanceof UniformPrimitives uniform?List.of(uniform.template()):primitives; }
        public boolean uniformPrimitives() { return primitives instanceof UniformPrimitives; }
        public Geometry withVertices(HostExecution.BufferGrant grant) {
            return new Geometry(key,world,generation,revision,transformRevision,materialRevision,grant,layout,topology,tintMode,motion,vertexCount,
                origin,current,previous,participation,primitives);
        }
        public void requireWindow(HostExecution.Window window) {
            if (window.device() != vertices.view().device() || window.recording() > vertices.validThroughRecording()
                || !window.buffers().contains(vertices))
                throw new IllegalArgumentException("Geometry lacks a current host resource grant");
        }
    }


    public record Withdrawal(SceneInputs.Key key, long world, long generation, long revision) {
        public Withdrawal {
            Objects.requireNonNull(key);
            if (world <= 0 || generation <= 0 || revision <= 0) throw new IllegalArgumentException("Invalid producer withdrawal");
        }
    }
}
