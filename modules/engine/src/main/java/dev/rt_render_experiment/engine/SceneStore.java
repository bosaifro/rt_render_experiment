package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.engine.abi.R2Abi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;


public final class SceneStore {
    public record Budget(int jobs,long bytes,long residentBytes) {
        public Budget(int jobs,long bytes) { this(jobs,bytes,Long.MAX_VALUE); }
        public Budget { if(jobs<=0 || bytes<=0 || residentBytes<=0)throw new IllegalArgumentException("Invalid preparation budget"); }
    }
    public record Revision(long serial,long world,List<SceneCompiler.Compiled> geometry,long bytes,
                           List<TextureInputs.Texture> textures,List<LightInputs.Source> lights) {
        public Revision(long serial,long world,List<SceneCompiler.Compiled> geometry,long bytes) { this(serial,world,geometry,bytes,List.of(),List.of()); }
        public Revision { geometry=List.copyOf(geometry);textures=List.copyOf(textures);lights=List.copyOf(lights); }
    }

    record Publication(Revision scene,Map<SceneInputs.Key,CompiledSource> sources) {
        Publication { sources=Map.copyOf(sources); }
    }
    public record CompiledSource(SceneInputs.PreparationRequest request,SceneCompiler.Compiled geometry,
                                 List<TextureInputs.Texture> textures,List<LightInputs.Source> lights,long bytes,SourceResidency.Bounds bounds,long reusedGeometryBytes) {
        public CompiledSource(SceneInputs.PreparationRequest request,SceneCompiler.Compiled geometry,List<TextureInputs.Texture> textures,List<LightInputs.Source> lights,
                              long bytes,SourceResidency.Bounds bounds) { this(request,geometry,textures,lights,bytes,bounds,0); }
        public CompiledSource { textures=List.copyOf(textures);lights=List.copyOf(lights); }
    }

    public record AppearanceUpdate(long resourceGeneration,long expectedRevision,TextureInputs.Texture texture) {
        public AppearanceUpdate {
            if(resourceGeneration<=0 || expectedRevision<=0 || texture.revision()<=expectedRevision)
                throw new IllegalArgumentException("Invalid appearance update");
        }
    }
    private record Appearance(long generation,TextureInputs.Texture texture) {}
    public record GroupStatistics(int pending,int members,long revisionFactBytes,long declarations,long publications,
                                  int sealed,int sealedMembers,long seals,long sealedPublications,long revokedSeals) {}
    private final SceneCompiler compiler;
    private final Budget budget;
    private final Thread owner=Thread.currentThread();
    private final Map<SceneInputs.Key,SceneInputs.PreparationRequest> requests=new LinkedHashMap<>();
    private final Map<SceneInputs.Key,SceneInputs.PreparationRequest> prepared=new HashMap<>();
    private Map<SceneInputs.Key,CompiledSource> entries=new LinkedHashMap<>();
    private final Map<SceneInputs.Key,Appearance> appearances=new HashMap<>();
    private Publication publication=new Publication(new Revision(0,0,List.of(),0),Map.of());
    private Publication selectedBase,selectedPublication;
    private long world,serial,nextRequest,pendingBytes;
    private boolean coherenceBlocked;
    private Map<SceneInputs.Key,Long> appearanceRevisions;
    private record TextureConsumer(SceneInputs.Key source,int index) {}
    private Map<SceneInputs.Key,List<TextureConsumer>> textureConsumers;
    private Set<SceneInputs.Key> retainedResourceKeys;
    private long cachedResidentBytes=-1;
    private final SourcePublicationGroups groups=new SourcePublicationGroups();

    public SceneStore(SceneCompiler compiler,Budget budget) { this.compiler=compiler;this.budget=budget; }
    public void world(long epoch) {
        requireOwner();if(epoch<=world)throw new IllegalArgumentException("World epochs must advance");
        world=epoch;clear();
    }
    void clear() {
        requireOwner();requests.clear();prepared.clear();entries.clear();appearances.clear();groups.clear();appearanceRevisions=null;pendingBytes=0;coherenceBlocked=false;
        publication=new Publication(new Revision(++serial,world,List.of(),0),Map.of());
        selectedBase=null;selectedPublication=null;
        textureConsumers=null;retainedResourceKeys=null;cachedResidentBytes=-1;
    }
    public SceneInputs.PreparationRequest request(SceneInputs.Key key,SceneInputs.Revision revision,long maximumBytes) {
        requireOwner();if(revision.world()!=world)throw new IllegalArgumentException("Foreign world request");
        if(groups.locked(key))throw new IllegalStateException("Complete captured predecessor has not reached scene publication");
        var previous=requests.get(key);
        long candidateBytes=Math.addExact(pendingBytes-(previous==null?0:previous.maximumBytes()),maximumBytes);
        if((previous==null && requests.size()>=budget.jobs()) || candidateBytes>budget.bytes())throw new IllegalStateException("Preparation backpressure");
        var request=new SceneInputs.PreparationRequest(Math.incrementExact(nextRequest),key,revision,maximumBytes);nextRequest=request.request();
        requests.put(key,request);prepared.remove(key);pendingBytes=candidateBytes;return request;
    }
    public boolean current(SceneInputs.PreparationRequest request) { requireOwner();return request.equals(requests.get(request.source())); }
    public void cancel(SceneInputs.PreparationRequest request) {
        requireOwner();if(current(request)) { groups.revoke(request.source());finish(request); }
    }

    void captured(List<SceneInputs.PreparationRequest> input) {
        requireOwner();
        if(input.stream().anyMatch(request->!current(request)))throw new IllegalArgumentException("Capture lacks its exact source reservation");
        for(var request:input)prepared.put(request.source(),request);
        groups.seal(entries,prepared);
    }
    void expected(SceneInputs.Key key,SceneInputs.Revision revision) { requireOwner();groups.expected(key,revision); }
    void related(SceneInputs.RelatedSources relation) { requireOwner();groups.relate(relation);install(entries); }
    boolean related(SceneInputs.Key key) { requireOwner();return groups.contains(key); }
    boolean locked(SceneInputs.Key key) { requireOwner();return groups.locked(key); }
    public GroupStatistics groupStatistics() { requireOwner();return groups.statistics(); }

    CompiledSource publishEmpty(SceneInputs.Key key,SceneInputs.Revision revision) {
        requireOwner();if(revision.world()!=world)throw new IllegalArgumentException("Foreign empty source world");
        groups.revoke(key);
        var request=new SceneInputs.PreparationRequest(nextRequest=Math.incrementExact(nextRequest),key,revision,1);
        var empty=new CompiledSource(request,null,List.of(),List.of(),0,null);
        var candidate=new LinkedHashMap<>(entries);candidate.put(key,empty);install(candidate);return empty;
    }


    public static long workBytes(SceneInputs.PreparedSource prepared) {
        requirePreparedCharge(prepared.content());
        long bytes=prepared.content().preparedBytes();
        for(var texture:prepared.textures())bytes=Math.addExact(bytes,TextureCompiler.compiledBytes(texture));
        return Math.addExact(bytes,Math.multiplyExact((long)prepared.lights().size(),R2Abi.SourceRecord.SIZE));
    }
    public static CompiledSource compile(SceneInputs.PreparedSource prepared) { return compile(new SceneCompiler(),prepared,null); }
    static CompiledSource compile(SceneInputs.PreparedSource prepared,SceneCompiler.Compiled previous) { return compile(new SceneCompiler(),prepared,previous); }

    Map<SceneInputs.Key,SceneCompiler.Compiled> compilationPredecessors(List<SceneInputs.PreparedSource> sources) {
        requireOwner();var previous=new HashMap<SceneInputs.Key,SceneCompiler.Compiled>();
        for(var source:sources) {
            var entry=entries.get(source.content().request().source());
            if(entry!=null && entry.geometry()!=null && entry.request().expected().world()==source.content().request().expected().world()
                && entry.request().expected().resources()==source.content().request().expected().resources())previous.put(entry.request().source(),entry.geometry());
        }
        return Map.copyOf(previous);
    }
    private static void requirePreparedCharge(SceneInputs.PreparationResult content) {
        long minimum=0;
        for(var input:content.geometry()) {
            for(var primitive:input.primitives())minimum=Math.addExact(minimum,SceneCompiler.primitiveBytes(primitive.corners().size()));
            for(var cell:input.fluids())minimum=Math.addExact(minimum,cell.occlusionBytes());
            for(var model:input.opticalModels())minimum=Math.addExact(minimum,ModelBoundary.maximumBytes(model));
        }
        if(content.preparedBytes()<minimum)throw new IllegalArgumentException("Prepared geometry/coverage charge omits supplied facts");
    }
    private static CompiledSource compile(SceneCompiler compiler,SceneInputs.PreparedSource prepared,SceneCompiler.Compiled previous) {
        var content=prepared.content();var request=content.request();
        if(content.status()!=SceneInputs.PreparationStatus.READY || content.geometry().size()>1)
            throw new IllegalArgumentException("One source request requires one complete geometry result");
        requirePreparedCharge(content);
        var input=content.geometry().isEmpty()?null:content.geometry().getFirst();
        boolean reused=input!=null && compiler.canReuse(previous,input);
        var geometry=input==null?null:reused?compiler.withInputRevision(previous,input):compiler.compile(input);
        if(geometry!=null && (!geometry.input().key().equals(request.source()) || !geometry.input().revision().equals(request.expected())
            || geometry.bytes()>content.preparedBytes()))throw new IllegalArgumentException("Result source, revision or geometry size differs from request");
        var textureCompiler=new TextureCompiler();var textures=prepared.textures().stream().map(textureCompiler::compile).toList();
        long bytes=(geometry==null?0:geometry.bytes())+Math.multiplyExact((long)prepared.lights().size(),R2Abi.SourceRecord.SIZE);
        for(var texture:textures)bytes=Math.addExact(bytes,TextureCompiler.compiledBytes(texture));
        var result=new CompiledSource(request,geometry,textures,prepared.lights(),bytes,contentBounds(geometry,prepared.lights()),reused?geometry.bytes():0);validate(result);return result;
    }
    private static SourceResidency.Bounds contentBounds(SceneCompiler.Compiled geometry,List<LightInputs.Source> lights) {
        double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        double magnitude=0;
        if(geometry!=null) {
            var input=geometry.input();var data=geometry.positions();var matrix=input.current();double[] origin={input.origin().x(),input.origin().y(),input.origin().z()};
            var arrays=new ArrayList<java.nio.ByteBuffer>();arrays.add(data);
            var strides=new ArrayList<Integer>();strides.add(SceneCompiler.POSITION_STRIDE);
            for(var boundary:geometry.modelBoundaries()) { arrays.add(boundary.positions());strides.add(12); }
            for(int array=0;array<arrays.size();array++) {
            data=arrays.get(array);int stride=strides.get(array);
            for(int vertex=0;vertex<data.remaining()/stride;vertex++)for(int axis=0;axis<3;axis++) {
                double value=origin[axis]+matrix.get(axis,3);magnitude=Math.max(magnitude,Math.abs(origin[axis])+Math.abs(matrix.get(axis,3)));
                for(int component=0;component<3;component++) { double term=(double)matrix.get(axis,component)*data.getFloat(vertex*stride+component*4);value+=term;magnitude=Math.max(magnitude,Math.abs(term)); }
                lo[axis]=Math.min(lo[axis],value);hi[axis]=Math.max(hi[axis],value);
            }
            }




            for(var cell:input.fluids())for(int corner=0;corner<8;corner++) {
                double[] point={cell.position().x()+((corner&1)==0?0.0:1.0),cell.position().y()+((corner&2)==0?0.0:1.0),cell.position().z()+((corner&4)==0?0.0:1.0)};
                for(int axis=0;axis<3;axis++) {
                    double value=origin[axis]+matrix.get(axis,3);magnitude=Math.max(magnitude,Math.abs(origin[axis])+Math.abs(matrix.get(axis,3)));
                    for(int component=0;component<3;component++) { double term=matrix.get(axis,component)*point[component];value+=term;magnitude=Math.max(magnitude,Math.abs(term)); }
                    lo[axis]=Math.min(lo[axis],value);hi[axis]=Math.max(hi[axis],value);
                }
            }
        }
        for(var light:lights)if(light instanceof LightInputs.Point point) {
            double[] position={point.position().x(),point.position().y(),point.position().z()};
            for(int axis=0;axis<3;axis++) { lo[axis]=Math.min(lo[axis],position[axis]-point.radius());hi[axis]=Math.max(hi[axis],position[axis]+point.radius());magnitude=Math.max(magnitude,Math.abs(position[axis])+point.radius()); }
        }
        if(!Double.isFinite(lo[0]))return null;


        double padding=Math.max(1e-4,4.0*Math.ulp((float)magnitude));
        return new SourceResidency.Bounds(new SceneInputs.Origin(lo[0]-padding,lo[1]-padding,lo[2]-padding),new SceneInputs.Origin(hi[0]+padding,hi[1]+padding,hi[2]+padding));
    }
    private static void validate(CompiledSource source) {
        var textures=new HashSet<SceneInputs.Key>();
        for(var texture:source.textures())if(texture.key().equals(new SceneInputs.Key(0,0)) || !textures.add(texture.key()))
            throw new IllegalArgumentException("Duplicate/reserved prepared texture identity");
        var parts=new HashSet<LightCompiler.Association>();
        if(source.geometry()!=null)for(var part:source.geometry().parts()) {
            var surface=part.surface();
            requireTexture(textures,surface.colorResource());requireTexture(textures,surface.layers().coverage());
            requireTexture(textures,surface.layers().overlay());
            if(surface.layers().emission()!=MaterialInputs.Emission.NONE)requireTexture(textures,surface.emissionResource());
            parts.add(new LightCompiler.Association(source.request().source(),part.key(),part.ordinal()));
        }
        if(source.geometry()!=null)for(var boundary:source.geometry().modelBoundaries())for(var primitive:boundary.source().surfaces()) {
            var surface=primitive.surface();requireTexture(textures,surface.colorResource());requireTexture(textures,surface.layers().coverage());
            requireTexture(textures,surface.layers().overlay());
            if(surface.layers().emission()!=MaterialInputs.Emission.NONE)requireTexture(textures,surface.emissionResource());
        }
        var identities=new HashSet<SceneInputs.Key>();
        for(var light:source.lights()) {
            if(!identities.add(light.key()))throw new IllegalArgumentException("Duplicate source light identity");
            if(light instanceof LightInputs.Area area && !parts.contains(new LightCompiler.Association(area.geometry(),area.part(),area.ordinal())))
                throw new IllegalArgumentException("Source light lacks its exact prepared primitive");
        }
    }
    private static void requireTexture(Set<SceneInputs.Key> textures,SceneInputs.Key key) {
        if(!key.equals(new SceneInputs.Key(0,0)) && !textures.contains(key))throw new IllegalArgumentException("Incomplete source resource dependency: "+key);
    }
    public boolean publish(SceneInputs.PreparationResult result) { return publish(SceneInputs.PreparedSource.geometryOnly(result)); }
    public boolean publish(SceneInputs.PreparedSource prepared) {
        requireOwner();var result=prepared.content();if(!current(result.request()))return false;
        if(result.status()!=SceneInputs.PreparationStatus.READY) { finish(result.request());return false; }
        var previous=entries.get(result.request().source());return publishCompiled(compile(compiler,prepared,previous==null?null:previous.geometry()));
    }

    public boolean publishCompiled(CompiledSource result) {
        requireOwner();var request=result.request();if(!current(request))return false;
        validateCompiled(result);
        var normalized=normalize(result);var candidate=new LinkedHashMap<>(entries);candidate.put(request.source(),normalized);
        install(candidate);finish(request);return true;
    }

    public List<Boolean> publishCompiledBatch(List<CompiledSource> results) {
        return publishCompiledCohorts(results.stream().map(List::of).toList());
    }

    public boolean publishCompiledCohort(List<CompiledSource> results) {
        return publishCompiledCohorts(List.of(results)).getFirst();
    }

    List<Boolean> publishCompiledCohorts(List<List<CompiledSource>> cohorts) {
        requireOwner();var accepted=new ArrayList<Boolean>();var candidate=new LinkedHashMap<>(entries);var seen=new HashSet<SceneInputs.Key>();
        for(var cohort:cohorts) {
            if(cohort.isEmpty())throw new IllegalArgumentException("Empty source cohort");
            var keys=new HashSet<SceneInputs.Key>();
            for(var result:cohort)if(!keys.add(result.request().source()))throw new IllegalArgumentException("Two cohort members claim one source");
            boolean current=cohort.stream().allMatch(result->current(result.request()));accepted.add(current);if(!current)continue;
            for(var result:cohort) {
                if(!seen.add(result.request().source()))throw new IllegalArgumentException("Two cohorts claim one current source request");
                validateCompiled(result);candidate.put(result.request().source(),normalize(result));
            }
        }
        if(!seen.isEmpty()) {
            install(candidate);
            for(int i=0;i<cohorts.size();i++)if(accepted.get(i))for(var result:cohorts.get(i))finish(result.request());
        }
        return List.copyOf(accepted);
    }
    private static void validateCompiled(CompiledSource result) {
        var request=result.request();
        if(result.geometry()!=null && (!result.geometry().input().key().equals(request.source()) || !result.geometry().input().revision().equals(request.expected())
            || result.geometry().bytes()>request.maximumBytes()))throw new IllegalArgumentException("Invalid compiled publication");
        validate(result);
    }
    private CompiledSource normalize(CompiledSource source) {
        var textures=new ArrayList<TextureInputs.Texture>();boolean changed=false;
        for(var texture:source.textures()) {
            var before=texture;
            var appearance=appearances.get(texture.key());
            if(appearance!=null && appearance.generation()==source.request().expected().resources()) {
                if(!TextureCompiler.sameLayout(appearance.texture(),texture))throw new IllegalArgumentException("Appearance update changed prepared resource layout");
                if(texture.revision()<appearance.texture().revision())texture=appearance.texture();
                else if(texture.revision()==appearance.texture().revision() && !TextureCompiler.sameContent(texture,appearance.texture()))
                    throw new IllegalArgumentException("One texture revision describes different content");
            }
            textures.add(texture);changed|=texture!=before;
        }
        return changed?new CompiledSource(source.request(),source.geometry(),textures,source.lights(),source.bytes(),source.bounds(),source.reusedGeometryBytes()):source;
    }

    public void updateAppearance(List<AppearanceUpdate> updates) {
        requireOwner();if(updates.isEmpty())return;
        var proposed=new HashMap<>(appearances);var changed=new HashSet<SceneInputs.Key>();var compiler=new TextureCompiler();
        var consumers=textureConsumers();var affected=new HashSet<SceneInputs.Key>();
        var expectedRevisions=appearanceRevisions();
        for(var update:updates) {
            var texture=compiler.compile(update.texture());boolean found=false;
            if(!changed.add(texture.key()))throw new IllegalArgumentException("Duplicate appearance update");
            var expected=expectedRevisions.get(texture.key());
            if(expected==null || expected.longValue()!=update.expectedRevision())throw new IllegalArgumentException("Appearance update has a stale or absent resource baseline");
            for(var consumer:consumers.getOrDefault(texture.key(),List.of())) {
                var source=entries.get(consumer.source());var old=source.textures().get(consumer.index());
                found=true;
                if(source.request().expected().resources()!=update.resourceGeneration()
                    || old.revision()>texture.revision() || !TextureCompiler.sameLayout(old,texture)
                    || old.revision()==texture.revision() && !TextureCompiler.sameContent(old,texture))
                    throw new IllegalArgumentException("Appearance update has a stale revision or a different resource layout");
                affected.add(consumer.source());
            }
            if(!found)throw new IllegalArgumentException("Appearance update has no resident consumer");
            proposed.put(texture.key(),new Appearance(update.resourceGeneration(),texture));
        }
        var before=new HashMap<>(appearances);appearances.clear();appearances.putAll(proposed);
        try {
            var candidate=new LinkedHashMap<>(entries);
            for(var key:affected)candidate.put(key,normalize(entries.get(key)));
            install(candidate,true);
        } catch(RuntimeException|Error failure) { appearances.clear();appearances.putAll(before);throw failure; }
    }
    private Map<SceneInputs.Key,List<TextureConsumer>> textureConsumers() {
        if(textureConsumers==null) {
            var index=new HashMap<SceneInputs.Key,List<TextureConsumer>>();
            for(var entry:entries.entrySet())for(int i=0;i<entry.getValue().textures().size();i++)
                index.computeIfAbsent(entry.getValue().textures().get(i).key(),ignored->new ArrayList<>()).add(new TextureConsumer(entry.getKey(),i));
            textureConsumers=index;
        }
        return textureConsumers;
    }
    private void install(Map<SceneInputs.Key,CompiledSource> candidate) {
        install(candidate,false);
    }

    private void install(Map<SceneInputs.Key,CompiledSource> candidate,boolean appearanceOnly) {
        if(residentBytes(candidate,publication.sources())>budget.residentBytes())throw new IllegalStateException("Scene resident byte pressure");
        var visible=new LinkedHashMap<>(candidate);var complete=new ArrayList<SourcePublicationGroups.Group>();boolean waiting=false;
        for(var group:groups.pending()) {
            if(group.ready(candidate)) { complete.add(group);continue; }
            waiting=true;
            for(var key:group.required().keySet()) {
                var previous=publication.sources().get(key);
                if(previous==null)visible.remove(key);else visible.put(key,normalize(previous));
            }
        }
        var completeSeals=new ArrayList<SourcePublicationGroups.Group>();
        for(var group:groups.sealed()) {
            if(group.ready(candidate)) {
                for(var key:group.required().keySet())visible.put(key,candidate.get(key));
                completeSeals.add(group);
            } else {
                waiting=true;
                for(var key:group.required().keySet()) {
                    var previous=publication.sources().get(key);
                    if(previous==null)visible.remove(key);else visible.put(key,normalize(previous));
                }
            }
        }
        boolean same=visible.equals(publication.sources());var next=same?publication.scene():assemble(visible);
        entries=candidate;coherenceBlocked=waiting || next==null;appearanceRevisions=null;
        if(!appearanceOnly)textureConsumers=null;
        retainedResourceKeys=null;cachedResidentBytes=-1;
        if(next!=null) {
            if(!same) { publication=new Publication(next,visible);serial=next.serial();selectedBase=null;selectedPublication=null; }
            groups.published(complete,completeSeals);
        }
        if(!appearanceOnly && !appearances.isEmpty())appearances.keySet().retainAll(textureConsumers().keySet());
    }
    private Revision assemble(Map<SceneInputs.Key,CompiledSource> sources) {
        var geometry=new ArrayList<SceneCompiler.Compiled>();var textures=new java.util.TreeMap<SceneInputs.Key,TextureInputs.Texture>();
        var generations=new HashMap<SceneInputs.Key,Long>();var lights=new java.util.TreeMap<SceneInputs.Key,LightInputs.Source>();boolean conflict=false;
        for(var source:sources.values()) {
            if(source.geometry()!=null)geometry.add(source.geometry());
            for(var texture:source.textures()) {
                var before=textures.putIfAbsent(texture.key(),texture);var generation=generations.putIfAbsent(texture.key(),source.request().expected().resources());
                if(before!=null) {
                    if(generation.longValue()!=source.request().expected().resources() || !TextureCompiler.sameLayout(before,texture)
                        || before.revision()==texture.revision() && !TextureCompiler.sameContent(before,texture))
                        throw new IllegalArgumentException("One resource identity describes incompatible prepared content");
                    conflict |= before.revision()!=texture.revision();
                }
            }
            for(var light:source.lights())if(lights.putIfAbsent(light.key(),light)!=null)throw new IllegalArgumentException("Two sources own one light identity");
        }
        if(conflict)return null;



        for(var previous:publication.scene().textures()) {
            var candidate=textures.get(previous.key());
            if(candidate!=null && TextureCompiler.sameContent(previous,candidate))textures.put(previous.key(),previous);
        }
        geometry.sort(java.util.Comparator.comparing(compiled->compiled.input().key()));long bytes=geometry.stream().mapToLong(SceneCompiler.Compiled::bytes).sum();
        return new Revision(Math.incrementExact(serial),world,geometry,bytes,List.copyOf(textures.values()),List.copyOf(lights.values()));
    }
    private long residentBytes(Map<SceneInputs.Key,CompiledSource> a,Map<SceneInputs.Key,CompiledSource> b) {
        var geometry=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<java.nio.ByteBuffer,Boolean>());
        var boundaries=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ModelBoundary,Boolean>());
        var textures=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TextureInputs.Texture,Boolean>());
        var lights=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<LightInputs.Source,Boolean>());
        var sources=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<CompiledSource,Boolean>());sources.addAll(a.values());sources.addAll(b.values());
        long bytes=0;
        for(var source:sources) {
            if(source.geometry()!=null)bytes=Math.addExact(bytes,source.geometry().uniqueStorageBytes(geometry,boundaries));
            for(var texture:source.textures())if(textures.add(texture))bytes=Math.addExact(bytes,TextureCompiler.compiledBytes(texture));
            for(var light:source.lights())if(lights.add(light))bytes=Math.addExact(bytes,R2Abi.SourceRecord.SIZE);
        }


        for(var texture:publication.scene().textures())if(textures.add(texture))bytes=Math.addExact(bytes,TextureCompiler.compiledBytes(texture));
        return bytes;
    }
    public void remove(SceneInputs.Key key) {
        requireOwner();boolean related=groups.contains(key);groups.removed(key);var pending=requests.get(key);if(pending!=null)finish(pending);
        if(entries.containsKey(key) || publication.sources().containsKey(key)) {
            var candidate=new LinkedHashMap<>(entries);candidate.remove(key);

            if(publication.sources().containsKey(key)) { var retained=new LinkedHashMap<>(publication.sources());retained.remove(key);var next=assemble(retained);publication=new Publication(next,retained);serial=next.serial();selectedBase=null;selectedPublication=null;cachedResidentBytes=-1;retainedResourceKeys=null; }
            install(candidate);
        } else if(related)install(entries);
    }
    public Revision snapshot() { requireOwner();return publication.scene(); }

    public SceneInputs.Revision publishedRevision(SceneInputs.Key source) {
        requireOwner();var value=publication.sources().get(source);return value==null?null:value.request().expected();
    }
    Publication publication() { requireOwner();return publication; }







    Publication select(SourceResidency.Demand demand) {
        requireOwner();boolean all=true,same=selectedBase==publication;
        for(var entry:publication.sources().entrySet()) {
            boolean include=SourceResidency.include(entry.getValue().bounds(),demand);all&=include;
            if(same && include!=selectedPublication.sources().containsKey(entry.getKey()))same=false;
        }
        if(all) { selectedBase=null;selectedPublication=null;return publication; }
        if(same)return selectedPublication;
        var selected=new LinkedHashMap<SceneInputs.Key,CompiledSource>();
        publication.sources().forEach((key,value)->{ if(SourceResidency.include(value.bounds(),demand))selected.put(key,value); });
        var textureKeys=new HashSet<SceneInputs.Key>();var lightKeys=new HashSet<SceneInputs.Key>();
        for(var source:selected.values()) {
            for(var texture:source.textures())textureKeys.add(texture.key());
            for(var light:source.lights())lightKeys.add(light.key());
        }


        var base=publication.scene();var geometry=base.geometry().stream().filter(g->selected.containsKey(g.input().key())).toList();
        var scene=new Revision(serial=Math.incrementExact(serial),world,geometry,geometry.stream().mapToLong(SceneCompiler.Compiled::bytes).sum(),
            base.textures().stream().filter(t->textureKeys.contains(t.key())).toList(),base.lights().stream().filter(l->lightKeys.contains(l.key())).toList());
        selectedBase=publication;selectedPublication=new Publication(scene,selected);return selectedPublication;
    }





    public Map<SceneInputs.Key,Long> appearanceRevisions() {
        requireOwner();if(appearanceRevisions!=null)return appearanceRevisions;
        var revisions=new HashMap<SceneInputs.Key,Long>();
        for(var source:entries.values())for(var texture:source.textures())revisions.merge(texture.key(),texture.revision(),Math::min);
        appearanceRevisions=Map.copyOf(revisions);return appearanceRevisions;
    }
    public SourceResidency.Bounds bounds(SceneInputs.Key key) { requireOwner();var source=entries.get(key);return source==null?null:source.bounds(); }
    public boolean coherenceBlocked() { requireOwner();return coherenceBlocked; }
    public long residentBytes() { requireOwner();if(cachedResidentBytes<0)cachedResidentBytes=residentBytes(entries,publication.sources());return cachedResidentBytes; }
    public Set<SceneInputs.Key> resourceKeys() {
        requireOwner();
        if(retainedResourceKeys==null) {
            var keys=new HashSet<>(textureConsumers().keySet());
            for(var texture:publication.scene().textures())keys.add(texture.key());
            retainedResourceKeys=Set.copyOf(keys);
        }
        return retainedResourceKeys;
    }
    public int pendingJobs() { requireOwner();return requests.size(); }
    public long pendingBytes() { requireOwner();return pendingBytes; }
    private void finish(SceneInputs.PreparationRequest request) { requests.remove(request.source());prepared.remove(request.source());pendingBytes-=request.maximumBytes(); }
    private void requireOwner() { if(Thread.currentThread()!=owner)throw new IllegalStateException("Scene publication requires its owner thread"); }
}
