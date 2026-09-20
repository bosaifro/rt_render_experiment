package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;


public final class ModelVolumeCompiler {
    public enum Status { NOT_REQUESTED, NO_MODELS, READY, EXPLICIT_BOUNDARY, INCOMPLETE_MODEL, HIDDEN_INTERFACE,
        UNSUPPORTED_SURFACE, NON_UNIFORM_MEDIUM, RESOURCE_UNAVAILABLE, GEOMETRY_UNSUPPORTED, PRESSURE, ORIGIN_UNSUPPORTED, INCOMPLETE_FLUID, UNSUPPORTED_FLUID }





    public record Report(Status status,int volumes,int derivedMeshes,long inspectedTextureBytes,long geometricWork,boolean reused,String detail,int fluidRegions,int fluidCells) {
        public Report(Status status,int volumes,int derivedMeshes,long inspectedTextureBytes,long geometricWork,boolean reused,String detail) {
            this(status,volumes,derivedMeshes,inspectedTextureBytes,geometricWork,reused,detail,0,0);
        }
        public int totalVolumes() { return Math.addExact(volumes,fluidRegions); }
        public static final Report NONE=new Report(Status.NOT_REQUESTED,0,0,0,0,false,"");
    }

    public record Limits(int models,int parts,long textureBytes,int fluidCells,long contactBytes) {
        public Limits(int models,int parts,long textureBytes,int fluidCells) { this(models,parts,textureBytes,fluidCells,32L<<20); }
        public Limits(int models,int parts,long textureBytes) { this(models,parts,textureBytes,4096); }
        public Limits { if(models<=0 || parts<=0 || textureBytes<=0 || fluidCells<=0 || contactBytes<=0)throw new IllegalArgumentException("Invalid model volume limits"); }
        public static Limits standard() { return new Limits(256,131072,16L<<20); }
    }
    private record Model(SceneInputs.Key geometry,SceneInputs.Key part,long ordinal,boolean fluid) {}

    private record Identity(java.util.Set<Model> members) {
        Identity { members=java.util.Set.copyOf(members); }
        Identity(Model model) { this(java.util.Set.of(model)); }
    }
    private record Key(long world,List<SceneCompiler.Compiled> geometry,List<TextureInputs.Texture> textures,boolean fluids) {}
    private record Cached(Key key,Map<SceneCompiler.Compiled,SceneCompiler.Compiled> geometry,MediumInputs.Domain domain,Report report,List<SceneCompiler.Compiled> contactInputs) {}
    public record Result(FrameScene scene,Report report) {}
    private static final SceneInputs.Key NEUTRAL=new SceneInputs.Key(0,0);
    private final Limits limits;
    private final SceneCompiler compiler=new SceneCompiler();
    private final SceneMediaCompiler verifier;
    private Map<Identity,Long> identities=Map.of();
    private Cached cached;
    private long world,nextIdentity;
    public ModelVolumeCompiler() { this(Limits.standard()); }
    public ModelVolumeCompiler(Limits limits) { this(limits,new SceneMediaCompiler()); }
    ModelVolumeCompiler(Limits limits,SceneMediaCompiler verifier) { this.limits=java.util.Objects.requireNonNull(limits);this.verifier=java.util.Objects.requireNonNull(verifier); }
    public void clear() { cached=null;identities=Map.of();verifier.clear(); }
    public Result prepare(FrameScene source) {
        if(!source.modelVolumesRequested()) {
            if(cached!=null) { cached=null;verifier.clear(); }


            return new Result(source,Report.NONE);
        }
        var revision=source.revision();
        if(revision.world()<world)throw new IllegalArgumentException("Stale model volume world");
        if(world!=revision.world()) { clear();world=revision.world();nextIdentity=0; }
        var relevant=new ArrayList<SceneCompiler.Compiled>();var resourceKeys=new HashSet<SceneInputs.Key>();int parts=0,fluidCellCount=0;
        for(var mesh:revision.geometry()) {


            if(!mesh.input().fluids().isEmpty() && !source.fluidVolumesRequested())return failed(source,Status.INCOMPLETE_MODEL,"Fluid regions require dedicated optical volume compilation");
            fluidCellCount=Math.addExact(fluidCellCount,mesh.input().fluids().size());
            if(fluidCellCount>limits.fluidCells())return failed(source,Status.PRESSURE,"Fluid cell preparation budget");
            var fluidOrdinals=new HashMap<SceneInputs.Key,java.util.Set<Long>>();
            for(var cell:mesh.input().fluids())fluidOrdinals.computeIfAbsent(cell.part(),k->new HashSet<>()).add(cell.ordinal());
            boolean needed=!mesh.input().fluids().isEmpty();
            for(var boundary:mesh.modelBoundaries())if(boundary.source().surfaces().stream().anyMatch(p->potential(p.surface()))) {
                needed=true;
                for(var primitive:boundary.source().surfaces())if(boundary.source().occludedOrdinals().contains(primitive.ordinal())) {
                    if(++parts>limits.parts())return failed(source,Status.PRESSURE,"Hidden model volume part budget");
                    resourceKeys.add(primitive.surface().colorResource());
                }
            }
            for(var part:mesh.parts())if(potential(part.surface())) {
                if(++parts>limits.parts())return failed(source,Status.PRESSURE,"Model volume part budget");
                needed=true;if(!fluidOrdinals.getOrDefault(part.key(),java.util.Set.of()).contains(part.ordinal()/6))resourceKeys.add(part.surface().colorResource());
            }
            if(needed)relevant.add(mesh);
        }
        if(relevant.isEmpty()) { clear();return new Result(source,new Report(Status.NO_MODELS,0,0,0,0,false,"")); }
        var textures=revision.textures().stream().filter(t->resourceKeys.contains(t.key())).sorted(java.util.Comparator.comparing(TextureInputs.Texture::key)).toList();
        var key=new Key(world,List.copyOf(relevant),textures,source.fluidVolumesRequested());
        if(cached!=null && cached.key.equals(key) && (cached.contactInputs==null || cached.contactInputs.equals(revision.geometry())))return result(source,cached,true);
        long[] bytes={0};
        try {
            var fluids=source.fluidVolumesRequested()?FluidVolumeCompiler.prepare(relevant,limits.fluidCells()):FluidVolumeCompiler.Prepared.EMPTY;
            if(fluids.regions().size()>limits.models())throw new Unsupported(Status.PRESSURE,"Fluid region count budget");
            var byKey=new HashMap<SceneInputs.Key,TextureInputs.Texture>();for(var texture:textures)byKey.put(texture.key(),texture);
            var colors=new HashMap<SceneInputs.Key,SceneInputs.Vec3>();var candidates=new ArrayList<ModelVolumeJoins.Model>();
            for(var mesh:relevant) {
                var expected=new HashMap<SceneInputs.Key,HashSet<Long>>();
                for(var part:mesh.parts())if(potential(part.surface()) && !fluids.faces().contains(new FluidVolumeCompiler.Face(mesh,part))) {
                    if(part.surface().boundary()!=SceneInputs.Boundary.UNQUALIFIED)throw new Unsupported(Status.EXPLICIT_BOUNDARY,"Source already declares an identified boundary");
                    expected.computeIfAbsent(part.key(),k->new HashSet<>()).add(part.ordinal());
                }
                for(var boundary:mesh.modelBoundaries())if(expected.containsKey(boundary.source().part())
                    || boundary.source().surfaces().stream().anyMatch(p->potential(p.surface()))) {
                    var model=boundary.source();




                    if(candidates.size()+fluids.regions().size()>=limits.models())throw new Unsupported(Status.PRESSURE,"Model volume count budget");
                    if(mesh.input().participation().rayMask()!=Participation.WORLD || !mesh.input().participation().primaryVisible())
                        throw new Unsupported(Status.UNSUPPORTED_SURFACE,"Model lacks complete physical world participation");
                    var ordinals=new HashSet<Long>();MediumInputs.Definition definition=null;
                    for(var primitive:model.surfaces()) {
                        var surface=primitive.surface();ordinals.add(primitive.ordinal());
                        if(surface.boundary()!=SceneInputs.Boundary.UNQUALIFIED)throw new Unsupported(Status.EXPLICIT_BOUNDARY,"Source already declares an identified boundary");
                        var kind=MediumProfiles.kind(surface.material(),surface.properties());
                        if(kind==null || surface.coverage()!=SceneInputs.Coverage.DIELECTRIC || !surface.doubleSided() || surface.layer()!=0 || surface.layerSeparation()!=0
                            || (surface.properties()&(dev.rt_render_experiment.contract.SurfaceProperties.THIN|dev.rt_render_experiment.contract.SurfaceProperties.TRANSLUCENT))!=0
                            || !surface.layers().overlay().equals(NEUTRAL) || surface.layers().emission()==MaterialInputs.Emission.MERGED || surface.layers().modelResponse())
                            throw new Unsupported(Status.UNSUPPORTED_SURFACE,"Surface modifiers do not establish a homogeneous optical interior");
                        var color=colors.get(surface.colorResource());
                        if(color==null) { color=uniform(surface.colorResource(),byKey,bytes);colors.put(surface.colorResource(),color); }
                        for(var corner:primitive.corners()) {
                            var tint=corner.tint();
                            var sample=new SceneInputs.Vec3(color.x()*decode(tint.r(),surface.layers().tintEncoding()),color.y()*decode(tint.g(),surface.layers().tintEncoding()),
                                color.z()*decode(tint.b(),surface.layers().tintEncoding()));
                            var value=MediumProfiles.definition(kind,sample);
                            if(definition!=null && !definition.equals(value))throw new Unsupported(Status.NON_UNIFORM_MEDIUM,"Model corners/surfaces imply different interior coefficients");
                            definition=value;
                        }
                    }
                    ordinals.removeAll(model.occludedOrdinals());var rendered=expected.remove(model.part());
                    if(!ordinals.equals(rendered==null?java.util.Set.of():rendered))throw new Unsupported(Status.INCOMPLETE_MODEL,"Complete model and rendered primitive membership disagree");
                    candidates.add(new ModelVolumeJoins.Model(mesh,boundary,java.util.Objects.requireNonNull(definition)));
                }
                if(!expected.isEmpty())throw new Unsupported(Status.INCOMPLETE_MODEL,"Potential volume has no complete prepared model");
            }
            ModelVolumeJoins.Prepared joined;
            var contactRequests=new ArrayList<OpaqueContacts.Request>();Map<OpaqueContacts.Request,List<OpaqueContacts.Solid>> matched;
            var preparationBudget=new ConvexVolume.Budget(4_000_000);
            try {
                joined=ModelVolumeJoins.prepare(candidates,preparationBudget);
                for(var face:joined.unmatched())contactRequests.add(face.request());
                for(var region:fluids.regions())contactRequests.addAll(region.contacts());
                matched=OpaqueContacts.match(contactRequests,revision.geometry(),limits.parts(),preparationBudget);
            }
            catch(IllegalArgumentException failure) { throw new Unsupported(Status.HIDDEN_INTERFACE,failure.getMessage(),-1); }
            catch(IllegalStateException failure) { throw new Unsupported(Status.PRESSURE,failure.getMessage(),-1); }
            var next=new HashMap<Identity,Long>();var mappings=new HashMap<SceneCompiler.Compiled,Map<SceneCompiler.Part,Long>>();
            var contactMappings=new HashMap<SceneCompiler.Compiled,Map<SceneCompiler.Part,Long>>();var definitions=new ArrayList<MediumInputs.Volume>();
            var clips=new HashMap<SceneCompiler.Compiled,List<ClipGeometry.Contact>>();
            var seen=new HashSet<Model>();
            for(var region:joined.regions()) {
                var members=new HashSet<Model>();
                for(var candidate:region.models()) {
                    var model=new Model(candidate.mesh().input().key(),candidate.boundary().source().part(),0,false);
                    if(!seen.add(model))throw new Unsupported(Status.INCOMPLETE_MODEL,"Repeated model identity");members.add(model);
                }
                var identity=new Identity(members);var id=identities.get(identity);
                if(id==null)id=Math.incrementExact(nextIdentity);nextIdentity=Math.max(nextIdentity,id);next.put(identity,id);
                for(var candidate:region.models())for(var primitive:candidate.boundary().source().surfaces())
                    if(!candidate.boundary().source().occludedOrdinals().contains(primitive.ordinal()))
                        mappings.computeIfAbsent(candidate.mesh(),k->new HashMap<>()).put(candidate.mesh().findPart(primitive.part(),primitive.ordinal()).orElseThrow(),id);
                for(var contact:joined.unmatched())if(region.models().contains(contact.model()))for(var solid:matched.get(contact.request())) {
                    if(!solid.clip().isEmpty()) { clips.computeIfAbsent(solid.mesh(),k->new ArrayList<>()).add(new ClipGeometry.Contact(solid.part(),id,solid.clip(),solid.supports()));continue; }
                    var previous=contactMappings.computeIfAbsent(solid.mesh(),k->new HashMap<>()).put(solid.part(),id);
                    if(previous!=null)throw new Unsupported(Status.HIDDEN_INTERFACE,"Opaque surface belongs to multiple contact regions");
                }
                definitions.add(new MediumInputs.Volume(id,region.definition()));
            }
            for(var region:fluids.regions()) {
                var anchor=region.anchor();var identity=new Identity(new Model(anchor.geometry(),anchor.part(),anchor.ordinal(),true));
                var id=identities.get(identity);if(id==null)id=Math.incrementExact(nextIdentity);nextIdentity=Math.max(nextIdentity,id);
                if(next.put(identity,id)!=null)throw new Unsupported(Status.INCOMPLETE_FLUID,"Repeated fluid region identity");
                for(var face:region.faces())mappings.computeIfAbsent(face.mesh(),k->new HashMap<>()).put(face.part(),id);
                for(var request:region.contacts())for(var solid:matched.get(request)) {
                    if(!solid.clip().isEmpty()) { clips.computeIfAbsent(solid.mesh(),k->new ArrayList<>()).add(new ClipGeometry.Contact(solid.part(),id,solid.clip(),solid.supports()));continue; }
                    if(contactMappings.computeIfAbsent(solid.mesh(),k->new HashMap<>()).put(solid.part(),id)!=null)
                        throw new Unsupported(Status.HIDDEN_INTERFACE,"Opaque surface belongs to multiple model/fluid regions");
                }
                definitions.add(new MediumInputs.Volume(id,region.definition()));
            }
            var derived=new HashMap<SceneCompiler.Compiled,SceneCompiler.Compiled>();
            var changed=new HashSet<SceneCompiler.Compiled>(mappings.keySet());changed.addAll(contactMappings.keySet());
            changed.addAll(clips.keySet());long contactBytes=0;
            for(var entry:clips.entrySet()) {
                contactBytes=Math.addExact(contactBytes,ClipGeometry.allocationBytes(entry.getKey(),entry.getValue()));
                if(contactBytes>limits.contactBytes())throw new Unsupported(Status.PRESSURE,"Clipped contact derivative byte budget");
            }
            for(var mesh:changed) {
                var before=cached==null?null:cached.geometry.get(mesh);
                var volumes=mappings.getOrDefault(mesh,Map.of());var contacts=contactMappings.getOrDefault(mesh,Map.of());
                var patches=clips.getOrDefault(mesh,List.of());
                for(var patch:patches)if(contacts.containsKey(patch.owner()))throw new Unsupported(Status.HIDDEN_INTERFACE,"Full and partial contact owners conflict");
                var value=sameVolumes(before,mesh,volumes,contacts) && before.clipContacts().equals(patches)?before:compiler.withVolumes(mesh,volumes,contacts);
                try { derived.put(mesh,value==before?value:compiler.withClipContacts(value,patches)); }
                catch(IllegalArgumentException failure) { throw new Unsupported(Status.HIDDEN_INTERFACE,failure.getMessage(),-1); }
                catch(IllegalStateException failure) { throw new Unsupported(Status.PRESSURE,failure.getMessage(),-1); }
            }
            var domain=new MediumInputs.Domain(definitions);
            var geometry=revision.geometry().stream().map(g->derived.getOrDefault(g,g)).toList();
            var candidateRevision=new SceneStore.Revision(revision.serial(),world,geometry,revision.bytes(),revision.textures(),revision.lights());
            SceneMediaCompiler.Work work;
            try { work=verifier.prepare(candidateRevision,domain).work(); }
            catch(IllegalArgumentException failure) { throw new Unsupported(Status.GEOMETRY_UNSUPPORTED,failure.getMessage(),-1); }
            catch(IllegalStateException failure) { throw new Unsupported(Status.PRESSURE,failure.getMessage(),-1); }
            identities=Map.copyOf(next);
            String detail=joined.interfaces()==0?"":"Joined "+candidates.size()+" model sources across "+joined.interfaces()+" hidden cover groups";
            if(!contactRequests.isEmpty())detail+=(detail.isEmpty()?"":"; ")+"Opaque contacts="+contactRequests.size()+" opaquePrimitives="+matched.values().stream().flatMap(List::stream).mapToInt(OpaqueContacts.Solid::primitiveCount).sum();
            if(!clips.isEmpty())detail+=" clippedContacts="+clips.values().stream().mapToInt(List::size).sum()+" traceDerivativeBytes="+contactBytes;
            cached=new Cached(key,Map.copyOf(derived),domain,new Report(Status.READY,joined.regions().size(),derived.size(),bytes[0],Math.addExact(work.predicates(),preparationBudget.used()),false,detail,fluids.regions().size(),fluids.cells()),
                !contactRequests.isEmpty()?List.copyOf(revision.geometry()):null);
            return result(source,cached,false);
        } catch(FluidVolumeCompiler.Unsupported failure) {
            var status=switch(failure.reason) {
                case SUPPORT -> Status.UNSUPPORTED_FLUID;case NEIGHBOR -> Status.INCOMPLETE_FLUID;
                case INTERFACE -> Status.HIDDEN_INTERFACE;case SURFACE -> Status.UNSUPPORTED_SURFACE;case PRESSURE -> Status.PRESSURE;
            };
            cached=new Cached(key,Map.of(),null,new Report(status,0,0,bytes[0],0,false,failure.getMessage(),0,fluidCellCount),List.copyOf(revision.geometry()));
            return result(source,cached,false);
        } catch(Unsupported failure) {
            cached=new Cached(key,Map.of(),null,new Report(failure.status,0,0,bytes[0],failure.work,false,failure.getMessage(),0,fluidCellCount),List.copyOf(revision.geometry()));

            return result(source,cached,false);
        }
    }
    private Result failed(FrameScene source,Status status,String detail) { cached=null;return new Result(source,new Report(status,0,0,0,0,false,detail)); }
    private static Result result(FrameScene source,Cached cached,boolean reused) {
        var report=cached.report;
        if(reused)report=new Report(report.status,report.volumes,report.derivedMeshes,0,0,true,report.detail,report.fluidRegions,report.fluidCells);
        return new Result(cached.domain==null?source:source.withCompiledVolumes(cached.geometry,cached.domain),report);
    }
    private static boolean sameVolumes(SceneCompiler.Compiled before,SceneCompiler.Compiled source,Map<SceneCompiler.Part,Long> mapping,Map<SceneCompiler.Part,Long> contacts) {
        if(before==null)return false;
        for(int i=0;i<source.parts().size();i++) {
            var part=source.parts().get(i);var id=mapping.get(part);var previous=before.parts().get(i);var contact=contacts.get(part);
            var role=id!=null?SceneInputs.Boundary.NESTED_VOLUME:SceneInputs.Boundary.OPAQUE_CONTACT;if(id==null)id=contact;
            if(id==null?previous!=part:previous.surface().boundary()!=role || previous.surface().medium()!=id)return false;
        }
        return true;
    }
    static boolean potential(SceneInputs.Surface surface) { return MediumProfiles.potential(surface); }
    private SceneInputs.Vec3 uniform(SceneInputs.Key key,Map<SceneInputs.Key,TextureInputs.Texture> textures,long[] inspected) {
        if(key.equals(NEUTRAL))return new SceneInputs.Vec3(1,1,1);
        var texture=textures.get(key);if(texture==null)throw new Unsupported(Status.RESOURCE_UNAVAILABLE,"Optical model requires copied immutable texture pixels");
        int rgb=-1;
        for(var level:texture.levels()) {
            var pixels=level.pixels();
            for(int offset=0;offset<pixels.remaining();offset+=4) {
                if(limits.textureBytes()-inspected[0]<4)throw new Unsupported(Status.PRESSURE,"Model volume texture budget");
                inspected[0]+=4;
                int value=Byte.toUnsignedInt(pixels.get(offset))<<16|Byte.toUnsignedInt(pixels.get(offset+1))<<8|Byte.toUnsignedInt(pixels.get(offset+2));
                if(rgb>=0 && rgb!=value)throw new Unsupported(Status.NON_UNIFORM_MEDIUM,"Texture RGB varies; no homogeneous interior is declared");rgb=value;
            }
        }
        return new SceneInputs.Vec3(decode((rgb>>>16)/255f,texture.encoding()),decode((rgb>>>8&255)/255f,texture.encoding()),decode((rgb&255)/255f,texture.encoding()));
    }
    private static float decode(float value,TextureInputs.Encoding encoding) {
        if(encoding==TextureInputs.Encoding.LINEAR)return value;
        value=Math.clamp(value,0,1);return value<=.04045f?value*(1/12.92f):(float)Math.pow((value+.055f)*(1/1.055f),2.4f);
    }
    private static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final Status status;
        private final long work;
        private Unsupported(Status status,String message) { this(status,message,0); }
        private Unsupported(Status status,String message,long work) { super(message);this.status=status;this.work=work; }
    }
}
