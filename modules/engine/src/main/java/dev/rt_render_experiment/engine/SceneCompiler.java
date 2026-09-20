package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.engine.abi.R2Abi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.SceneInputs;


public final class SceneCompiler {

    public static SceneInputs.Origin center(Compiled mesh,int primitiveIndex) {
        var part=mesh.parts().get(primitiveIndex);
        var origin=mesh.input().origin();var matrix=mesh.input().current();var positions=mesh.positions();
        double[] center={0,0,0};
        for(int vertex=0;vertex<part.vertices();vertex++) {
            int offset=(part.firstVertex()+vertex)*POSITION_STRIDE;
            double x=positions.getFloat(offset),y=positions.getFloat(offset+4),z=positions.getFloat(offset+8);
            for(int axis=0;axis<3;axis++)center[axis]+=(matrix.get(axis,0)*x+matrix.get(axis,1)*y+matrix.get(axis,2)*z+matrix.get(axis,3))/part.vertices();
        }
        return new SceneInputs.Origin(origin.x()+center[0],origin.y()+center[1],origin.z()+center[2]);
    }
    public static final int POSITION_STRIDE = R2Abi.PositionRecord.SIZE;
    public static final int CORNER_STRIDE = R2Abi.CornerRecord.SIZE;

    public static long primitiveBytes(int corners) {
        if(corners!=3 && corners!=4)throw new IllegalArgumentException("Expected a triangle or quad");
        return (long)corners*(POSITION_STRIDE+CORNER_STRIDE)+(corners==4?6:3)*4L;
    }
    public record Part(SceneInputs.Key key, long ordinal, SceneInputs.Surface surface,
                       int firstVertex, int vertices, int firstIndex, int indices) {}

    private record ResourceDependencies(Set<SceneInputs.Key> render,Set<SceneInputs.Key> required) {
        private static ResourceDependencies collect(List<Part> parts,List<ModelBoundary> boundaries) {
            var render=new java.util.HashSet<SceneInputs.Key>();
            for(var part:parts)add(render,part.surface());
            var required=new java.util.HashSet<>(render);
            for(var boundary:boundaries)for(var primitive:boundary.source().surfaces())add(required,primitive.surface());
            return new ResourceDependencies(Set.copyOf(render),Set.copyOf(required));
        }
        private static void add(Set<SceneInputs.Key> keys,SceneInputs.Surface surface) {
            keys.add(surface.colorResource());keys.add(surface.layers().coverage());keys.add(surface.layers().overlay());
            if(surface.layers().emission()!=MaterialInputs.Emission.NONE)keys.add(surface.emissionResource());
        }
    }

    private static final class PrimitiveLookup {
        private final List<Part> parts;
        private volatile int[] order;
        private PrimitiveLookup(List<Part> parts) { this.parts=parts; }
        private static int compare(Part part,SceneInputs.Key key,long ordinal) {
            int comparison=part.key().compareTo(key);
            return comparison==0?Long.compare(part.ordinal(),ordinal):comparison;
        }
        private java.util.Optional<Part> find(SceneInputs.Key key,long ordinal) {
            java.util.Objects.requireNonNull(key);
            var sorted=order;
            if(sorted==null)synchronized(this) {
                sorted=order;
                if(sorted==null) {
                    sorted=java.util.stream.IntStream.range(0,parts.size()).boxed()
                        .sorted((a,b)->compare(parts.get(a),parts.get(b).key(),parts.get(b).ordinal()))
                        .mapToInt(Integer::intValue).toArray();
                    order=sorted;
                }
            }
            int low=0,high=sorted.length-1;
            while(low<=high) {
                int middle=(low+high)>>>1;var part=parts.get(sorted[middle]);int comparison=compare(part,key,ordinal);
                if(comparison==0)return java.util.Optional.of(part);
                if(comparison<0)low=middle+1;else high=middle-1;
            }
            return java.util.Optional.empty();
        }
        private long bytes() { var sorted=order;return sorted==null?0:(long)sorted.length*Integer.BYTES; }
    }
    public static final class Compiled {
        private final SceneInputs.Geometry input;
        private final ByteBuffer positions, corners, indices;
        private final List<Part> parts;
        private final List<ModelBoundary> modelBoundaries;
        private final long boundaryBytes;
        private final PrimitiveLookup lookup;
        private final boolean sourceCompilation;
        private final ClipGeometry clip;
        private final ResourceDependencies resources;
        private final String topology, appearance, positionIdentity, deformationIdentity, shadingIdentity;
        private Compiled(SceneInputs.Geometry input, ByteBuffer positions, ByteBuffer corners, ByteBuffer indices, List<Part> parts,
                         String topology, String appearance, String positionIdentity,String deformationIdentity,String shadingIdentity,PrimitiveLookup lookup,
                         List<ModelBoundary> modelBoundaries,boolean sourceCompilation) {
            this(input,positions,corners,indices,parts,topology,appearance,positionIdentity,deformationIdentity,shadingIdentity,lookup,modelBoundaries,sourceCompilation,null);
        }
        private Compiled(SceneInputs.Geometry input,ByteBuffer positions,ByteBuffer corners,ByteBuffer indices,List<Part> parts,
                         String topology,String appearance,String positionIdentity,String deformationIdentity,String shadingIdentity,PrimitiveLookup lookup,
                         List<ModelBoundary> modelBoundaries,boolean sourceCompilation,ClipGeometry clip) {
            this(input,positions,corners,indices,parts,topology,appearance,positionIdentity,deformationIdentity,shadingIdentity,lookup,modelBoundaries,sourceCompilation,clip,null);
        }
        private Compiled(SceneInputs.Geometry input,ByteBuffer positions,ByteBuffer corners,ByteBuffer indices,List<Part> parts,
                         String topology,String appearance,String positionIdentity,String deformationIdentity,String shadingIdentity,PrimitiveLookup lookup,
                         List<ModelBoundary> modelBoundaries,boolean sourceCompilation,ClipGeometry clip,ResourceDependencies resources) {
            this.input = input; this.positions = immutable(positions); this.corners = immutable(corners);
            this.indices = immutable(indices); this.parts = List.copyOf(parts); this.topology = topology; this.appearance = appearance;
            this.sourceCompilation=sourceCompilation;
            this.clip=clip;
            this.positionIdentity=positionIdentity;
            this.deformationIdentity=deformationIdentity;
            this.shadingIdentity=shadingIdentity;
            this.lookup=lookup==null?new PrimitiveLookup(this.parts):lookup;
            this.modelBoundaries=List.copyOf(modelBoundaries);
            this.resources=resources==null?ResourceDependencies.collect(this.parts,this.modelBoundaries):resources;
            long bytes=0;for(var boundary:modelBoundaries)bytes=Math.addExact(bytes,boundary.bytes());this.boundaryBytes=bytes;
        }
        public SceneInputs.Geometry input() { return input; }
        public ByteBuffer positions() { return view(positions); }
        public ByteBuffer corners() { return view(corners); }
        public ByteBuffer indices() { return view(indices); }
        public List<Part> parts() { return parts; }
        public List<ModelBoundary> modelBoundaries() { return modelBoundaries; }
        public Set<SceneInputs.Key> renderResourceKeys() { return resources.render(); }
        public Set<SceneInputs.Key> requiredResourceKeys() { return resources.required(); }

        public java.util.Optional<Part> findPart(SceneInputs.Key key,long ordinal) { return lookup.find(key,ordinal); }

        public long primitiveIndexBytes() { return lookup.bytes(); }
        public int vertexCount() { return positions.remaining() / POSITION_STRIDE; }
        public int triangleCount() { return indices.remaining() / 12; }
        public long renderBytes() { return (long)positions.remaining() + corners.remaining() + indices.remaining(); }
        public long boundaryBytes() { return boundaryBytes; }
        public long bytes() { return Math.addExact(Math.addExact(renderBytes(),boundaryBytes()),clip==null?0:clip.logicalBytes); }
        List<ClipGeometry.Contact> clipContacts() { return clip==null?List.of():clip.contacts; }
        ByteBuffer tracePositions() { return view(clip==null?positions:clip.positions); }
        ByteBuffer traceCorners() { return view(clip==null?corners:clip.corners); }
        ByteBuffer traceIndices() { return view(clip==null?indices:clip.indices); }
        int traceVertexCount() { return tracePositions().remaining()/POSITION_STRIDE; }
        int traceTriangleCount() { return traceIndices().remaining()/12; }
        String traceTopologyHash() { return clip==null?topology:clip.topology; }
        String tracePositionHash() { return clip==null?positionIdentity:clip.positionIdentity; }
        public String topologyHash() { return topology; }
        public String appearanceHash() { return appearance; }
        public String positionHash() { return positionIdentity; }
        public String deformationHash() { return deformationIdentity; }
        public String shadingHash() { return shadingIdentity; }
        private static ByteBuffer immutable(ByteBuffer bytes) { return bytes.isReadOnly()?bytes:bytes.asReadOnlyBuffer(); }

        boolean sharesStorage(Compiled other) { return positions==other.positions && corners==other.corners && indices==other.indices && clip==other.clip && modelBoundaries.equals(other.modelBoundaries); }
        long uniqueStorageBytes(java.util.Set<ByteBuffer> buffers,java.util.Set<ModelBoundary> boundaries) {
            long bytes=0;
            if(buffers.add(positions))bytes+=positions.remaining();if(buffers.add(corners))bytes+=corners.remaining();if(buffers.add(indices))bytes+=indices.remaining();
            if(clip!=null) {
                if(buffers.add(clip.positions))bytes+=clip.positions.remaining();if(buffers.add(clip.corners))bytes+=clip.corners.remaining();if(buffers.add(clip.indices))bytes+=clip.indices.remaining();
                bytes+=clip.contacts.stream().mapToLong(ClipGeometry.Contact::factBytes).sum();
            }
            for(var boundary:modelBoundaries)if(boundaries.add(boundary))bytes=Math.addExact(bytes,boundary.bytes());return bytes;
        }
        private static ByteBuffer view(ByteBuffer value) { return value.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
    }
    public Compiled compile(SceneInputs.Geometry input) {
        List<SceneInputs.Primitive> prepared=input.primitives();
        if(!input.fluids().isEmpty()) {
            prepared=new ArrayList<>(prepared); var fluids=new FluidCompiler();
            for(var cell:input.fluids())prepared.addAll(fluids.compile(cell));
        }
        int vertexCount = 0, indexCount = 0;
        for (var primitive : prepared) {
            vertexCount = Math.addExact(vertexCount, primitive.corners().size());
            indexCount = Math.addExact(indexCount, primitive.corners().size() == 4 ? 6 : 3);
        }
        ByteBuffer positions = bytes(Math.multiplyExact(vertexCount, POSITION_STRIDE));
        ByteBuffer corners = bytes(Math.multiplyExact(vertexCount, CORNER_STRIDE));
        ByteBuffer indices = bytes(Math.multiplyExact(indexCount, 4));
        List<Part> parts = new ArrayList<>();
        MessageDigest topology = digest(), appearance = digest();
        int vertex = 0, index = 0;
        var seen = new java.util.HashSet<String>();
        for (var primitive : prepared) {
            String identity = primitive.part().high() + ":" + primitive.part().low() + ":" + primitive.ordinal();
            if (!seen.add(identity)) throw new IllegalArgumentException("Duplicate source primitive " + identity);
            var surface = primitive.surface();
            var a = primitive.corners().get(0).position();
            var b = primitive.corners().get(1).position();
            var c = primitive.corners().get(2).position();
            double nx = (double)(b.y()-a.y())*(c.z()-a.z())-(double)(b.z()-a.z())*(c.y()-a.y());
            double ny = (double)(b.z()-a.z())*(c.x()-a.x())-(double)(b.x()-a.x())*(c.z()-a.z());
            double nz = (double)(b.x()-a.x())*(c.y()-a.y())-(double)(b.y()-a.y())*(c.x()-a.x());
            double length = Math.sqrt(nx*nx + ny*ny + nz*nz);
            if (!(length > 0) && primitive.corners().size()==4) {
                var d=primitive.corners().get(3).position();
                nx=(double)(d.y()-c.y())*(a.z()-c.z())-(double)(d.z()-c.z())*(a.y()-c.y());
                ny=(double)(d.z()-c.z())*(a.x()-c.x())-(double)(d.x()-c.x())*(a.z()-c.z());
                nz=(double)(d.x()-c.x())*(a.y()-c.y())-(double)(d.y()-c.y())*(a.x()-c.x());
                length=Math.sqrt(nx*nx+ny*ny+nz*nz);
            }


            if (!(length>0)) { nx=0; ny=1; nz=0; length=1; }
            float offset = surface.layer() * surface.layerSeparation();
            int cornerIndex=vertex;
            for (var corner : primitive.corners()) {
                var p = corner.position();
                R2Abi.PositionRecord.value(positions,cornerIndex*POSITION_STRIDE,p.x() + (float)(nx/length)*offset,
                    p.y() + (float)(ny/length)*offset,p.z() + (float)(nz/length)*offset,0);
                int base=cornerIndex*CORNER_STRIDE;
                R2Abi.CornerRecord.uv(corners,base,corner.u(),corner.v());
                R2Abi.CornerRecord.material(corners,base,surface.material());
                R2Abi.CornerRecord.properties(corners,base,surface.properties());
                var tint = corner.tint();
                R2Abi.CornerRecord.tint(corners,base,tint.r(),tint.g(),tint.b(),tint.a());
                var normal = corner.normal();
                R2Abi.CornerRecord.normal(corners,base,normal.x(),normal.y(),normal.z(),0);
                var tangent = corner.tangent();
                R2Abi.CornerRecord.tangent(corners,base,tangent.x(),tangent.y(),tangent.z(),0);
                R2Abi.CornerRecord.layerUv(corners,base,corner.emissionUv().x(),corner.emissionUv().y(),corner.overlayTexel().x(),corner.overlayTexel().y());
                var emissionTint=corner.emissionTint();
                R2Abi.CornerRecord.emissionTint(corners,base,emissionTint.r(),emissionTint.g(),emissionTint.b(),emissionTint.a());
                cornerIndex++;
            }
            int count = primitive.corners().size();
            indices.putInt(vertex).putInt(vertex+1).putInt(vertex+2);
            if (count == 4) indices.putInt(vertex+2).putInt(vertex+3).putInt(vertex);
            int elementCount = count == 4 ? 6 : 3;
            parts.add(new Part(primitive.part(), primitive.ordinal(), surface, vertex, count, index, elementCount));
            ByteBuffer signature = bytes(40).putLong(primitive.part().high()).putLong(primitive.part().low())
                .putLong(primitive.ordinal()).putInt(count).putInt(surface.coverage().ordinal())
                .putInt(surface.doubleSided() ? 1 : 0).putInt(surface.layer());
            topology.update(signature.flip());
            appearance.update(surface.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            vertex += count; index += elementCount;
        }
        indices.flip();
        MessageDigest positionDigest=digest(); positionDigest.update(positions.duplicate());
        byte[] positionSignature=positionDigest.digest();
        MessageDigest deformation=digest();deformation.update(positionSignature);MessageDigest shading=digest();
        for(int base=0;base<corners.capacity();base+=CORNER_STRIDE) {
            appearance.update(corners.slice(base,R2Abi.CornerRecord.NORMAL));
            shading.update(corners.slice(base+R2Abi.CornerRecord.NORMAL,R2Abi.CornerRecord.LAYERUV-R2Abi.CornerRecord.NORMAL));
            appearance.update(corners.slice(base+R2Abi.CornerRecord.LAYERUV,CORNER_STRIDE-R2Abi.CornerRecord.LAYERUV));
        }
        byte[] shadingSignature=shading.digest();deformation.update(shadingSignature);
        return new Compiled(input, positions, corners, indices, parts, HexFormat.of().formatHex(topology.digest()),
            HexFormat.of().formatHex(appearance.digest()),HexFormat.of().formatHex(positionSignature),HexFormat.of().formatHex(deformation.digest()),HexFormat.of().formatHex(shadingSignature),null,
            input.opticalModels().stream().map(ModelBoundary::compile).toList(),true);
    }

    boolean canReuse(Compiled previous,SceneInputs.Geometry input) {
        if(!canReuseGeometry(previous,input))return false;
        return sameFrameFacts(previous.input,input);
    }

    static boolean sameFrameFacts(SceneInputs.Geometry before,SceneInputs.Geometry input) {
        return before.origin().equals(input.origin()) && sameTransform(before.current(),input.current()) && sameTransform(before.previous(),input.previous())
            && before.previousValid()==input.previousValid() && before.motion()==input.motion() && before.participation().equals(input.participation());
    }

    boolean canReuseGeometry(Compiled previous,SceneInputs.Geometry input) {
        if(previous==null || !previous.sourceCompilation)return false;
        var before=previous.input;
        return before.key().equals(input.key()) && before.revision().world()==input.revision().world() && before.revision().resources()==input.revision().resources()
            && before.primitives().equals(input.primitives()) && before.fluids().equals(input.fluids()) && before.opticalModels().equals(input.opticalModels());
    }
    private static boolean sameTransform(SceneInputs.Transform a,SceneInputs.Transform b) {
        for(int row=0;row<3;row++)for(int column=0;column<4;column++)
            if(Float.floatToRawIntBits(a.get(row,column))!=Float.floatToRawIntBits(b.get(row,column)))return false;return true;
    }

    Compiled withInputRevision(Compiled compiled,SceneInputs.Geometry input) {
        if(compiled.clip!=null && input!=compiled.input)throw new IllegalArgumentException("A clipping derivative cannot be rebound to another source pose or revision");
        return new Compiled(input,compiled.positions,compiled.corners,compiled.indices,compiled.parts,compiled.topology,compiled.appearance,compiled.positionIdentity,compiled.deformationIdentity,compiled.shadingIdentity,compiled.lookup,compiled.modelBoundaries,compiled.sourceCompilation,compiled.clip,compiled.resources);
    }

    Compiled withoutParts(Compiled source,java.util.Set<SceneInputs.Key> removed) {
        if(!source.sourceCompilation)throw new IllegalArgumentException("Source withdrawal requires original prepared content");
        var g=source.input;
        var input=new SceneInputs.Geometry(g.key(),g.revision(),g.origin(),g.current(),g.previous(),g.previousValid(),g.motion(),g.participation(),
            g.primitives().stream().filter(p->!removed.contains(p.part())).toList(),g.fluids().stream().filter(c->!removed.contains(c.part())).toList(),
            g.opticalModels().stream().filter(m->!removed.contains(m.part())).toList());
        var value=compile(input);
        return new Compiled(input,value.positions,value.corners,value.indices,value.parts,value.topology,value.appearance,value.positionIdentity,
            value.deformationIdentity,value.shadingIdentity,value.lookup,value.modelBoundaries,false);
    }

    Compiled withClipContacts(Compiled source,List<ClipGeometry.Contact> contacts) {
        if(contacts.isEmpty())return source;
        if(!source.clipContacts().isEmpty())throw new IllegalArgumentException("Clipped contact derivative already present");
        return new Compiled(source.input,source.positions,source.corners,source.indices,source.parts,source.topology,source.appearance,source.positionIdentity,
            source.deformationIdentity,source.shadingIdentity,source.lookup,source.modelBoundaries,false,new ClipGeometry(source,contacts),source.resources);
    }

    Compiled withVolumes(Compiled source,java.util.Map<Part,Long> models) {
        return withVolumes(source,models,java.util.Map.of());
    }
    Compiled withVolumes(Compiled source,java.util.Map<Part,Long> models,java.util.Map<Part,Long> contacts) {
        var parts=source.parts.stream().map(p->{
            var id=models.get(p);var contact=contacts.get(p);
            if(id!=null && contact!=null)throw new IllegalArgumentException("Surface has conflicting boundary owners");
            var surface=id!=null?p.surface().withNestedVolume(id):contact!=null?p.surface().withOpaqueContact(contact):p.surface();
            return surface==p.surface()?p:new Part(p.key(),p.ordinal(),surface,p.firstVertex(),p.vertices(),p.firstIndex(),p.indices());
        }).toList();
        var hash=digest();hash.update(source.appearance.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(var part:parts)hash.update(part.surface().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Compiled(source.input,source.positions,source.corners,source.indices,parts,source.topology,HexFormat.of().formatHex(hash.digest()),
            source.positionIdentity,source.deformationIdentity,source.shadingIdentity,null,source.modelBoundaries,false,null,source.resources);
    }
    private static ByteBuffer bytes(int count) { return ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
