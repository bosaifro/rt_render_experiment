package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;






public final class ModelBoundary {
    public record Component(int firstTriangle,int triangles,int boundaryEdges,int nonManifoldEdges,int windingConflicts,int degenerateTriangles,double signedVolume) {
        public boolean edgeClosed() { return boundaryEdges==0 && nonManifoldEdges==0 && windingConflicts==0 && degenerateTriangles==0; }
    }
    private record Position(int x,int y,int z) {
        private static Position of(SceneInputs.Vec3 p) { return new Position(bits(p.x()),bits(p.y()),bits(p.z())); }
        private static int bits(float value) { return Float.floatToIntBits(value==0?0:value); }
    }
    private record Edge(int lo,int hi) {}
    private static final class Incidence {
        private final int first;
        private int count,orientation;
        private Incidence(int first) { this.first=first; }
    }
    private final SceneInputs.OpticalModel source;
    private final ByteBuffer positions,triangles;
    private final List<Component> components;
    private final String identity;
    private ModelBoundary(SceneInputs.OpticalModel source,ByteBuffer positions,ByteBuffer triangles,List<Component> components,String identity) {
        this.source=source;this.positions=positions.asReadOnlyBuffer();this.triangles=triangles.asReadOnlyBuffer();
        this.components=List.copyOf(components);this.identity=identity;
    }
    public SceneInputs.OpticalModel source() { return source; }
    public ByteBuffer positions() { return positions.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }

    public ByteBuffer triangles() { return triangles.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
    public List<Component> components() { return components; }
    public String identity() { return identity; }

    public long bytes() { return 64L+positions.remaining()+triangles.remaining()+32L*components.size()+occludedBytes(source); }
    private static long occludedBytes(SceneInputs.OpticalModel model) {
        long bytes=8L*model.occludedOrdinals().size();
        for(var primitive:model.surfaces())if(model.occludedOrdinals().contains(primitive.ordinal()))
            bytes=Math.addExact(bytes,SceneCompiler.primitiveBytes(primitive.corners().size()));
        return bytes;
    }
    public static long maximumBytes(SceneInputs.OpticalModel model) {
        long bytes=64L+occludedBytes(model);
        for(var primitive:model.surfaces())bytes=Math.addExact(bytes,maximumPrimitiveBytes(primitive.corners().size()));
        return bytes;
    }

    public static long maximumPrimitiveBytes(int corners) {
        if(corners!=3 && corners!=4)throw new IllegalArgumentException("Expected prepared triangle or quad");
        return corners*12L+(corners==4?2:1)*16L+32;
    }
    public static ModelBoundary compile(SceneInputs.OpticalModel model) {
        var vertices=new ArrayList<SceneInputs.Vec3>();var byPosition=new HashMap<Position,Integer>();
        int triangleCount=0;for(var primitive:model.surfaces())triangleCount=Math.addExact(triangleCount,primitive.corners().size()==4?2:1);
        int[] parents=new int[triangleCount];for(int i=0;i<parents.length;i++)parents[i]=i;
        var data=ByteBuffer.allocate(Math.multiplyExact(triangleCount,16)).order(ByteOrder.LITTLE_ENDIAN);
        var edges=new HashMap<Edge,Incidence>();int triangle=0;
        for(int surface=0;surface<model.surfaces().size();surface++) {
            var primitive=model.surfaces().get(surface);int[] indices=new int[primitive.corners().size()];
            for(int i=0;i<indices.length;i++) {
                var point=primitive.corners().get(i).position();var key=Position.of(point);Integer index=byPosition.get(key);
                if(index==null) { index=vertices.size();vertices.add(point);byPosition.put(key,index); }indices[i]=index;
            }
            int[] split=indices.length==4?new int[]{0,1,2,2,3,0}:new int[]{0,1,2};
            for(int k=0;k<split.length;k+=3) {
                int a=indices[split[k]],b=indices[split[k+1]],c=indices[split[k+2]];
                data.putInt(a).putInt(b).putInt(c).putInt(surface);
                addEdge(edges,parents,triangle,a,b);addEdge(edges,parents,triangle,b,c);addEdge(edges,parents,triangle,c,a);triangle++;
            }
        }
        var components=new java.util.TreeMap<Integer,int[]>();var volumes=new HashMap<Integer,Double>();
        for(int t=0;t<triangleCount;t++) {
            int root=root(parents,t);var counts=components.computeIfAbsent(root,k->new int[]{k,0,0,0,0,0});counts[1]++;
            var origin=vertices.get(data.getInt(root*16));
            var a=vertices.get(data.getInt(t*16));var b=vertices.get(data.getInt(t*16+4));var c=vertices.get(data.getInt(t*16+8));
            double abx=(double)b.x()-a.x(),aby=(double)b.y()-a.y(),abz=(double)b.z()-a.z();
            double acx=(double)c.x()-a.x(),acy=(double)c.y()-a.y(),acz=(double)c.z()-a.z();
            double nx=aby*acz-abz*acy,ny=abz*acx-abx*acz,nz=abx*acy-aby*acx;
            if(nx*nx+ny*ny+nz*nz==0)counts[5]++;
            double signed=(((double)a.x()-origin.x())*nx+((double)a.y()-origin.y())*ny+((double)a.z()-origin.z())*nz)/6;
            volumes.merge(root,signed,Double::sum);
        }
        for(var edge:edges.values()) {
            var counts=components.get(root(parents,edge.first));
            if(edge.count==1)counts[2]++;
            else if(edge.count!=2)counts[3]++;
            else if(edge.orientation!=0)counts[4]++;
        }
        var records=new ArrayList<Component>();
        for(var entry:components.entrySet()) { var c=entry.getValue();records.add(new Component(c[0],c[1],c[2],c[3],c[4],c[5],volumes.get(entry.getKey()))); }
        var packed=ByteBuffer.allocate(Math.multiplyExact(vertices.size(),12)).order(ByteOrder.LITTLE_ENDIAN);
        for(var point:vertices)packed.putFloat(point.x()).putFloat(point.y()).putFloat(point.z());packed.flip();data.flip();
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        digest.update(packed.duplicate());digest.update(data.duplicate());
        for(var primitive:model.surfaces()) {
            digest.update((primitive.part()+":"+primitive.ordinal()+":"+primitive.surface()+":"+model.occludedOrdinals().contains(primitive.ordinal()))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for(var corner:primitive.corners())digest.update(corner.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new ModelBoundary(model,packed,data,records,HexFormat.of().formatHex(digest.digest()));
    }
    private static void addEdge(HashMap<Edge,Incidence> edges,int[] parents,int triangle,int a,int b) {
        var key=new Edge(Math.min(a,b),Math.max(a,b));var edge=edges.get(key);
        if(edge==null) { edge=new Incidence(triangle);edges.put(key,edge); }
        else { int x=root(parents,triangle),y=root(parents,edge.first);parents[Math.max(x,y)]=Math.min(x,y); }
        edge.count++;edge.orientation+=Integer.compare(b,a);
    }
    private static int root(int[] parents,int id) {
        int root=id;while(parents[root]!=root)root=parents[root];
        while(parents[id]!=id) { int next=parents[id];parents[id]=root;id=next; }return root;
    }
}
