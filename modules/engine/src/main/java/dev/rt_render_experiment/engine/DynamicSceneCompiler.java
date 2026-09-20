package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.DynamicInputs;
import dev.rt_render_experiment.contract.SceneInputs;


public final class DynamicSceneCompiler {
    public record Work(int objects,int compiled,int reused,long reusedBytes) {}
    public record Statistics(long submittedPreparations,long submittedObjects,long reusedObjects,long reusedBytes) {}
    private record Entry(DynamicInputs.PreparedObject source,SceneCompiler.Compiled compiled) {}
    private final SceneCompiler compiler=new SceneCompiler();
    private final Thread owner=Thread.currentThread();
    private final boolean reuseGeometry;
    private Map<SceneInputs.Key,Entry> current=Map.of();
    private long world,publication;
    private Prepared reserved;
    private long submittedPreparations,submittedObjects,reusedObjects,reusedBytes;
    public DynamicSceneCompiler() { this(true); }

    DynamicSceneCompiler(boolean reuseGeometry) { this.reuseGeometry=reuseGeometry; }
    public Statistics statistics() { requireOwner();return new Statistics(submittedPreparations,submittedObjects,reusedObjects,reusedBytes); }
    public Prepared prepare(long world,long resources,List<DynamicInputs.PreparedObject> inputs,long maximumBytes) {
        requireOwner();
        if(world<=0 || world<this.world || resources<=0 || maximumBytes<=0)throw new IllegalArgumentException("Invalid dynamic publication");
        var entries=new LinkedHashMap<SceneInputs.Key,Entry>();long bytes=0,reusedBytes=0;int reused=0;
        for(var source:inputs) {
            if(entries.containsKey(source.key()))throw new IllegalArgumentException("Duplicate prepared object identity");
            long required=bytes;
            for(var primitive:source.primitives())required=Math.addExact(required,SceneCompiler.primitiveBytes(primitive.corners().size()));
            for(var model:source.opticalModels())required=Math.addExact(required,ModelBoundary.maximumBytes(model));
            if(required>maximumBytes)throw new IllegalStateException("Dynamic compilation byte pressure");
            Entry before=world==this.world?current.get(source.key()):null;
            var anchor=before==null?anchor(source):before.compiled.input().origin();
            float[] transform=source.transform().rows();
            transform[3]+=(float)(source.origin().x()-anchor.x());transform[7]+=(float)(source.origin().y()-anchor.y());transform[11]+=(float)(source.origin().z()-anchor.z());
            var placement=new SceneInputs.Transform(transform);
            var primitives=source.primitives();
            var models=source.opticalModels();


            if(!models.isEmpty())new SceneInputs.Geometry(source.key(),new SceneInputs.Revision(world,1,0,0,1,resources,1),source.origin(),source.transform(),source.transform(),
                false,source.motion(),source.participation(),primitives,List.of(),models);
            if(source.motion()==SceneInputs.Motion.DEFORMING) {
                primitives=bake(source,anchor,primitives);placement=SceneInputs.Transform.identity();
                if(!models.isEmpty()) {
                    var visible=new java.util.HashMap<PrimitiveKey,SceneInputs.Primitive>();
                    for(var primitive:primitives)visible.put(new PrimitiveKey(primitive.part(),primitive.ordinal()),primitive);
                    var posed=new ArrayList<SceneInputs.OpticalModel>();
                    for(var model:models) {
                        var hidden=bake(source,anchor,model.surfaces().stream().filter(p->model.occludedOrdinals().contains(p.ordinal())).toList());
                        var byId=new java.util.HashMap<Long,SceneInputs.Primitive>();for(var p:hidden)byId.put(p.ordinal(),p);
                        var surfaces=model.surfaces().stream().map(p->model.occludedOrdinals().contains(p.ordinal())?byId.get(p.ordinal()):visible.get(new PrimitiveKey(p.part(),p.ordinal()))).toList();
                        posed.add(new SceneInputs.OpticalModel(model.part(),surfaces,model.occludedOrdinals()));
                    }
                    models=List.copyOf(posed);
                }
            }
            var provisional=new SceneInputs.Geometry(source.key(),new SceneInputs.Revision(world,1,0,0,1,resources,1),anchor,placement,placement,
                before!=null,source.motion(),source.participation(),primitives,List.of(),models);
            var old=before==null?null:before.compiled;
            boolean reuse=reuseGeometry && compiler.canReuseGeometry(old,provisional);
            var compiled=reuse?old:compiler.compile(provisional);
            if(reuse) { reused++;reusedBytes=Math.addExact(reusedBytes,compiled.bytes()); }
            bytes=Math.addExact(bytes,compiled.bytes());
            if(bytes>maximumBytes)throw new IllegalStateException("Dynamic compilation byte pressure");
            boolean topology=old!=null && before.source.topologyRevision()==source.topologyRevision() && old.topologyHash().equals(compiled.topologyHash())
                && bakedReflection(before.source)==bakedReflection(source);
            boolean deformation=topology && old.deformationHash().equals(compiled.deformationHash());
            boolean appearance=old!=null && before.source.appearanceRevision()==source.appearanceRevision() && old.appearanceHash().equals(compiled.appearanceHash());
            boolean placed=old!=null && java.util.Arrays.equals(old.input().current().rows(),placement.rows()) && old.input().participation().equals(source.participation());
            var previous=old==null?provisional.revision():old.input().revision();
            var revision=new SceneInputs.Revision(world,bump(old,previous.topology(),topology),bump(old,previous.deformation(),deformation),
                bump(old,previous.placement(),placed),bump(old,previous.appearance(),appearance),resources,bump(old,previous.coverage(),topology));
            var finalInput=new SceneInputs.Geometry(source.key(),revision,anchor,placement,old==null?placement:old.input().current(),topology,
                source.motion(),source.participation(),primitives,List.of(),models);



            boolean retain=reuse && revision.equals(old.input().revision()) && SceneCompiler.sameFrameFacts(old.input(),finalInput);
            entries.put(source.key(),new Entry(source,retain?old:compiler.withInputRevision(compiled,finalInput)));
        }
        return new Prepared(world,publication,entries,bytes,new Work(entries.size(),entries.size()-reused,reused,reusedBytes));
    }
    private static long bump(SceneCompiler.Compiled before,long previous,boolean unchanged) { return before==null?1:unchanged?previous:Math.incrementExact(previous); }
    private record PrimitiveKey(SceneInputs.Key part,long ordinal) {}
    private static boolean bakedReflection(DynamicInputs.PreparedObject source) {
        return source.motion()==SceneInputs.Motion.DEFORMING && source.transform().determinant()<0;
    }
    private static List<SceneInputs.Primitive> bake(DynamicInputs.PreparedObject input,SceneInputs.Origin anchor,List<SceneInputs.Primitive> primitives) {
        var r=input.transform().rows();
        var matrix=new org.joml.Matrix3f(r[0],r[4],r[8],r[1],r[5],r[9],r[2],r[6],r[10]);
        var normalMatrix=new org.joml.Matrix3f(matrix).invert().transpose();
        double[] offset={input.origin().x()-anchor.x()+r[3],input.origin().y()-anchor.y()+r[7],input.origin().z()-anchor.z()+r[11]};
        var result=new ArrayList<SceneInputs.Primitive>();
        for(var primitive:primitives) {
            var corners=new ArrayList<SceneInputs.Corner>();
            for(var c:primitive.corners()) {
                var p=c.position();
                var position=new SceneInputs.Vec3((float)(offset[0]+(double)r[0]*p.x()+(double)r[1]*p.y()+(double)r[2]*p.z()),
                    (float)(offset[1]+(double)r[4]*p.x()+(double)r[5]*p.y()+(double)r[6]*p.z()),
                    (float)(offset[2]+(double)r[8]*p.x()+(double)r[9]*p.y()+(double)r[10]*p.z()));
                var n=normalMatrix.transform(new org.joml.Vector3f(c.normal().x(),c.normal().y(),c.normal().z()));
                var t=matrix.transform(new org.joml.Vector3f(c.tangent().x(),c.tangent().y(),c.tangent().z()));
                corners.add(new SceneInputs.Corner(position,c.u(),c.v(),c.tint(),new SceneInputs.Vec3(n.x,n.y,n.z),new SceneInputs.Vec3(t.x,t.y,t.z),c.emissionUv(),c.emissionTint(),c.overlayTexel()));
            }
            if(bakedReflection(input)) {


                java.util.Collections.reverse(corners.subList(1,corners.size()));
            }
            result.add(new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),primitive.surface(),corners));
        }
        return List.copyOf(result);
    }
    private static SceneInputs.Origin anchor(DynamicInputs.PreparedObject input) {
        var p=input.primitives().isEmpty()?new SceneInputs.Vec3(0,0,0):input.primitives().getFirst().corners().getFirst().position();
        var t=input.transform();double[] world={input.origin().x(),input.origin().y(),input.origin().z()};
        for(int axis=0;axis<3;axis++)world[axis]+=t.get(axis,0)*p.x()+t.get(axis,1)*p.y()+t.get(axis,2)*p.z()+t.get(axis,3);
        return new SceneInputs.Origin(Math.floor(world[0]/16)*16,Math.floor(world[1]/16)*16,Math.floor(world[2]/16)*16);
    }
    public final class Prepared {
        private final long world,predecessor,bytes;
        private final Map<SceneInputs.Key,Entry> entries;
        private final Work work;
        private boolean committed;
        private Prepared(long world,long predecessor,Map<SceneInputs.Key,Entry> entries,long bytes,Work work) {
            this.world=world;this.predecessor=predecessor;this.entries=Map.copyOf(entries);this.bytes=bytes;this.work=work;
        }
        public Work work() { return work; }
        public List<SceneCompiler.Compiled> geometry() { return entries.values().stream().map(Entry::compiled).sorted(java.util.Comparator.comparing(c->c.input().key())).toList(); }
        public long world() { return world; }
        public SceneStore.Revision revision(long serial) { return new SceneStore.Revision(serial,world,geometry(),bytes); }

        void reserve() {
            validateSubmission();
            if(reserved!=null && reserved!=this)throw new IllegalStateException("Dynamic predecessor already reserved by a frame");
            reserved=this;
        }
        void releaseReservation() { requireOwner();if(reserved==this)reserved=null; }
        private void validateSubmission() {
            requireOwner();
            if(committed || publication!=predecessor || world<DynamicSceneCompiler.this.world || reserved!=null && reserved!=this)
                throw new IllegalStateException("Stale or reserved dynamic predecessor");
        }

        public void submitted() {
            requireOwner();if(reserved!=null)throw new IllegalStateException("The recorded frame owns dynamic submission");
            commit();
        }
        void commitReserved() {
            requireOwner();if(reserved!=this)throw new IllegalStateException("Dynamic frame reservation was lost");
            commit();
        }
        private void commit() {
            validateSubmission();
            current=entries;DynamicSceneCompiler.this.world=world;publication++;committed=true;releaseReservation();
            submittedPreparations++;submittedObjects+=work.objects();reusedObjects+=work.reused();reusedBytes=Math.addExact(reusedBytes,work.reusedBytes());
        }
    }
    public void clear() {
        requireOwner();if(reserved!=null)throw new IllegalStateException("Cancel the recorded dynamic frame before clearing its predecessor");
        current=Map.of();publication++;
    }
    private void requireOwner() { if(Thread.currentThread()!=owner)throw new IllegalStateException("Dynamic publication requires its owner thread"); }
}
