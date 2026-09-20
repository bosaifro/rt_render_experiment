package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.engine.abi.R2Abi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;


final class ClipGeometry {
    record Support(SceneCompiler.Compiled mesh,SceneCompiler.Part part) {
        Support { java.util.Objects.requireNonNull(mesh);java.util.Objects.requireNonNull(part); }
    }

    record Contact(SceneCompiler.Part owner,long medium,List<SceneInputs.Vec3> corners,List<Support> supports) {
        Contact(SceneCompiler.Part owner,long medium,List<SceneInputs.Vec3> corners) { this(owner,medium,corners,List.of()); }
        Contact {
            java.util.Objects.requireNonNull(owner);corners=List.copyOf(corners);supports=List.copyOf(supports);
            if(medium<=0 || corners.size()!=3 && corners.size()!=4)throw new IllegalArgumentException("Invalid clipped medium contact");
        }
        int triangles() { return corners.size()==3?1:2; }
        long factBytes() { return 32L+12L*corners.size()+16L*supports.size(); }
    }
    final List<Contact> contacts;
    final ByteBuffer positions,corners,indices;
    final String topology,positionIdentity;
    final long logicalBytes;
    static long allocationBytes(SceneCompiler.Compiled source,List<Contact> contacts) {
        if(contacts.isEmpty())return 0;
        long count=contacts.stream().mapToLong(c->SceneCompiler.primitiveBytes(c.corners.size())).sum();
        return Math.addExact(Math.addExact(source.renderBytes(),count),contacts.stream().mapToLong(Contact::factBytes).sum());
    }
    ClipGeometry(SceneCompiler.Compiled source,List<Contact> contacts) {
        this.contacts=List.copyOf(contacts);var budget=new ConvexVolume.Budget(4_000_000);
        int vertices=source.vertexCount(),triangles=source.triangleCount();
        for(var contact:contacts) {
            OpaqueContacts.requireClip(source,contact,budget);
            vertices=Math.addExact(vertices,contact.corners.size());triangles=Math.addExact(triangles,contact.triangles());
        }
        var p=bytes(Math.multiplyExact(vertices,SceneCompiler.POSITION_STRIDE));p.put(source.positions());
        var c=bytes(Math.multiplyExact(vertices,SceneCompiler.CORNER_STRIDE));c.put(source.corners());
        var i=bytes(Math.multiplyExact(triangles,12));i.put(source.indices());
        int vertex=source.vertexCount();
        for(var contact:contacts) {
            for(var point:contact.corners) { R2Abi.PositionRecord.value(p,vertex*SceneCompiler.POSITION_STRIDE,point.x(),point.y(),point.z(),0);vertex++; }
            int first=vertex-contact.corners.size();i.putInt(first).putInt(first+1).putInt(first+2);
            if(contact.corners.size()==4)i.putInt(first+2).putInt(first+3).putInt(first);
        }
        p.position(0);c.position(0);i.flip();positions=p.asReadOnlyBuffer();corners=c.asReadOnlyBuffer();indices=i.asReadOnlyBuffer();
        logicalBytes=(long)p.remaining()+c.remaining()+i.remaining()+contacts.stream().mapToLong(Contact::factBytes).sum();
        var hash=digest();hash.update(source.topologyHash().getBytes(java.nio.charset.StandardCharsets.UTF_8));hash.update(indices.duplicate());
        topology=HexFormat.of().formatHex(hash.digest());hash=digest();hash.update(positions.duplicate());positionIdentity=HexFormat.of().formatHex(hash.digest());
    }

    static void requireSources(java.util.Collection<SceneCompiler.Compiled> geometry) {
        boolean needed=false;
        for(var mesh:geometry)for(var contact:mesh.clipContacts())if(!contact.supports.isEmpty())needed=true;
        if(!needed)return;
        var sources=new java.util.HashMap<SceneInputs.Key,SceneCompiler.Compiled>();for(var mesh:geometry)sources.put(mesh.input().key(),mesh);
        for(var mesh:geometry)for(var contact:mesh.clipContacts())for(var support:contact.supports) {
            var current=sources.get(support.mesh.input().key());
            if(current==null || current.input()!=support.mesh.input() || !current.parts().contains(support.part)
                || !current.positionHash().equals(support.mesh.positionHash()) || !current.shadingHash().equals(support.mesh.shadingHash()))
                throw new IllegalArgumentException("Clipped contact has a missing or stale opaque supporting source");
        }
    }
    private static ByteBuffer bytes(int count) { return ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
