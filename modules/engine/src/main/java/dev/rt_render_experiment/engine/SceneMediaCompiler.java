package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.SurfaceProperties;


public final class SceneMediaCompiler {
    public record Limits(int volumes,int triangles,long predicates) {
        public Limits(int volumes,int triangles) { this(volumes,triangles,4_000_000); }
        public Limits { if(volumes<=0 || triangles<=0 || predicates<=0)throw new IllegalArgumentException("Invalid medium compilation limits"); }
    }
    public record Work(int compiledVolumes,int reusedVolumes,int triangles,long logicalBytes,long predicates) {
        public static final Work NONE=new Work(0,0,0,0,0);
    }
    private record Key(long world,MediumInputs.Domain definitions,List<SceneCompiler.Compiled> geometry) {}
    private record Volume(MediumInputs.Volume definition,VolumeBoundary bounds,int depth) {}
    private record Catalogue(Key key,List<Volume> volumes,int triangles,InitialMedia.ClipBoundaries clipBoundaries) {}
    private static final class Builder {
        private final SceneInputs.Geometry reference;
        private final List<ConvexVolume.Triangle> triangles=new ArrayList<>();
        private Builder(SceneInputs.Geometry reference) { this.reference=reference; }
    }
    private final Limits limits;
    private Catalogue cached;
    public SceneMediaCompiler() { this(new Limits(256,131072)); }
    public SceneMediaCompiler(Limits limits) { this.limits=java.util.Objects.requireNonNull(limits); }
    public void clear() { cached=null; }
    public Prepared prepare(SceneStore.Revision scene,MediumInputs.Domain domain) {
        if(scene.world()<=0 || scene.serial()<=0)throw new IllegalArgumentException("Invalid medium scene publication");
        if(domain.volumes().size()>limits.volumes())throw new IllegalStateException("Medium volume preparation pressure");
        ClipGeometry.requireSources(scene.geometry());
        var relevant=new ArrayList<SceneCompiler.Compiled>();
        for(var geometry:scene.geometry()) {
            if(geometry.input().revision().world()!=scene.world())throw unsupported("Geometry belongs to another world");
            boolean identified=false;
            for(var part:geometry.parts()) {
                var surface=part.surface();
                if(surface.boundary()!=SceneInputs.Boundary.UNQUALIFIED) {
                    if((geometry.input().participation().rayMask()&Participation.WORLD)!=Participation.WORLD || !geometry.input().participation().primaryVisible())
                        throw unsupported("Identified volume lacks complete physical ray participation");
                    identified=true;
                    if(surface.boundary()==SceneInputs.Boundary.OPAQUE_CONTACT && (!MediumProfiles.opaqueContact(surface) || !OpaqueContacts.flatNormals(geometry,part)))
                        throw unsupported("Opaque contact lacks a supported opaque response or flat source normals");
                } else if(MediumProfiles.potential(surface))
                    throw unsupported("An unclassified potentially volumetric surface remains in the domain");
            }
            if(identified || !geometry.clipContacts().isEmpty())relevant.add(geometry);
        }
        relevant.sort(java.util.Comparator.comparing(g->g.input().key()));
        var key=new Key(scene.world(),domain,List.copyOf(relevant));
        if(cached!=null && cached.key.equals(key))return new Prepared(scene,cached,new Work(0,cached.volumes.size(),0,bytes(cached),0));
        if(cached!=null && cached.key.world==key.world && cached.key.geometry.equals(key.geometry)
            && cached.key.definitions.volumes().stream().map(MediumInputs.Volume::identity).toList().equals(domain.volumes().stream().map(MediumInputs.Volume::identity).toList())) {


            var definitions=new HashMap<Long,MediumInputs.Volume>();for(var volume:domain.volumes())definitions.put(volume.identity(),volume);
            var rebound=cached.volumes.stream().map(volume->new Volume(definitions.get(volume.definition.identity()),volume.bounds,volume.depth)).toList();
            cached=new Catalogue(key,rebound,cached.triangles,cached.clipBoundaries);
            return new Prepared(scene,cached,new Work(0,rebound.size(),0,bytes(cached),0));
        }
        var definitions=new HashMap<Long,MediumInputs.Volume>();for(var volume:domain.volumes())definitions.put(volume.identity(),volume);
        var builders=new HashMap<Long,Builder>();int triangles=0;
        for(var geometry:relevant)for(var part:geometry.parts())if(part.surface().boundary()!=SceneInputs.Boundary.UNQUALIFIED) {
            long identity=part.surface().medium();if(!definitions.containsKey(identity))throw unsupported("Identified surface has no medium definition");
            var builder=builders.computeIfAbsent(identity,k->new Builder(geometry.input()));
            var positions=geometry.positions();var indices=geometry.indices();
            for(int i=0;i<part.indices();i+=3) {
                if(++triangles>limits.triangles())throw new IllegalStateException("Medium triangle preparation pressure");
                var points=new ConvexVolume.Point[3];
                for(int corner=0;corner<3;corner++) {
                    int offset=indices.getInt((part.firstIndex()+i+corner)*4)*SceneCompiler.POSITION_STRIDE;
                    points[corner]=ConvexVolume.point(builder.reference,geometry.input(),positions.getFloat(offset),positions.getFloat(offset+4),positions.getFloat(offset+8));
                }


                builder.triangles.add(part.surface().boundary()==SceneInputs.Boundary.OPAQUE_CONTACT
                    ?new ConvexVolume.Triangle(points[0],points[2],points[1]):new ConvexVolume.Triangle(points[0],points[1],points[2]));
            }
        }
        for(var geometry:relevant)for(var contact:geometry.clipContacts()) {
            if(!definitions.containsKey(contact.medium()))throw unsupported("Clipped contact lacks a medium definition");
            var builder=builders.computeIfAbsent(contact.medium(),k->new Builder(geometry.input()));
            var points=contact.corners().stream().map(p->ConvexVolume.point(builder.reference,geometry.input(),p.x(),p.y(),p.z())).toList();
            triangles=Math.addExact(triangles,contact.triangles());if(triangles>limits.triangles())throw new IllegalStateException("Clipped medium triangle pressure");
            builder.triangles.add(new ConvexVolume.Triangle(points.get(0),points.get(2),points.get(1)));
            if(points.size()==4)builder.triangles.add(new ConvexVolume.Triangle(points.get(2),points.get(0),points.get(3)));
        }
        if(!builders.keySet().equals(definitions.keySet()))throw unsupported("Medium definition has no complete rendered boundary");
        var bounds=new ArrayList<VolumeBoundary>();var budget=new ConvexVolume.Budget(limits.predicates());
        for(var definition:domain.volumes()) {
            var builder=builders.get(definition.identity());
            try { bounds.add(ConvexVolume.compile(builder.reference,builder.triangles,budget)); }
            catch(IllegalArgumentException notConvex) { bounds.add(TriangleVolume.compile(builder.reference,builder.triangles,budget)); }
        }
        int[] depths=new int[bounds.size()];


        var relations=new BoundaryRelations.Surface[bounds.size()];
        var relationOrigin=domain.volumes().isEmpty()?null:builders.get(domain.volumes().getFirst().identity()).reference.origin();
        for(int a=0;a<bounds.size();a++)for(int b=a+1;b<bounds.size();b++) {
            var x=bounds.get(a);var y=bounds.get(b);
            if(x.strictlyContains(y,budget))depths[b]++;
            else if(y.strictlyContains(x,budget))depths[a]++;
            else if(!x.separatedBounds(y,budget)) {
                for(int index:new int[]{a,b})if(relations[index]==null) {
                    var builder=builders.get(domain.volumes().get(index).identity());
                    relations[index]=BoundaryRelations.prepare(builder.reference,builder.triangles,relationOrigin,budget);
                }
                switch(BoundaryRelations.classify(x,y,relations[a],relations[b],budget)) {
                    case A_CONTAINS_B -> depths[b]++;
                    case B_CONTAINS_A -> depths[a]++;
                    case DISJOINT -> { }
                }
            }
        }
        var volumes=new ArrayList<Volume>();for(int i=0;i<bounds.size();i++)volumes.add(new Volume(domain.volumes().get(i),bounds.get(i),depths[i]));
        volumes.sort(java.util.Comparator.comparingInt(Volume::depth).thenComparingLong(v->v.definition.identity()));
        var result=new Catalogue(key,List.copyOf(volumes),triangles,new InitialMedia.ClipBoundaries(relevant,triangles));cached=result;
        return new Prepared(scene,result,new Work(volumes.size(),0,triangles,bytes(result),budget.used()));
    }


    private static long bytes(Catalogue catalogue) { return catalogue.clipBoundaries.logicalBytes()+catalogue.volumes.stream().mapToLong(v->48L+v.bounds.logicalBytes()).sum(); }
    public static final class Prepared {
        private final long world,revision;
        private final Catalogue catalogue;
        private final Work work;
        private Prepared(SceneStore.Revision scene,Catalogue catalogue,Work work) { world=scene.world();revision=scene.serial();this.catalogue=catalogue;this.work=work; }
        public Work work() { return work; }
        public RenderFrame classify(RenderFrame frame) {
            var eye=frame.eye();double clearance=CameraProjection.MAXIMUM_RAY_DISTANCE;var enclosures=new ArrayList<MediumInputs.Enclosure>();
            for(var volume:catalogue.volumes) {
                double distance=volume.bounds.clearance(eye),error=volume.bounds.uncertainty(eye,frame.origin());
                if(Math.abs(distance)<=error)throw unsupported("Eye is on or numerically indistinguishable from a medium boundary");
                clearance=Math.min(clearance,Math.abs(distance)-error);
                if(distance>0)enclosures.add(new MediumInputs.Enclosure(volume.definition.identity(),volume.definition.medium()));
            }
            if(enclosures.size()>dev.rt_render_experiment.contract.MediumEncoding.CAPACITY)throw unsupported("Initial nesting exceeds the path-state capacity");
            var supplied=frame.initialMedia();
            if(supplied.isPresent() && (supplied.orElseThrow().world()!=world || supplied.orElseThrow().sceneRevision()!=revision
                || !supplied.orElseThrow().position().equals(eye) || !supplied.orElseThrow().enclosures().equals(enclosures)))
                throw unsupported("Supplied initial state disagrees with compiled geometry");
            return frame.withClassifiedMedia(new MediumInputs.Origin(world,revision,eye,Math.nextDown((float)clearance),enclosures),catalogue.clipBoundaries);
        }
    }
    private static IllegalArgumentException unsupported(String reason) { return new IllegalArgumentException("Automatic medium classification: "+reason); }
}
