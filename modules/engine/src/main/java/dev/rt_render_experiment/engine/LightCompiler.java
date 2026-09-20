package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.engine.abi.R2Abi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.SceneInputs;


public final class LightCompiler {
    public static final int CELL_METRES=16;
    public enum Reuse { ENABLED, REBUILD }
    private final Reuse reuse;
    public LightCompiler() { this(Reuse.ENABLED); }
    public LightCompiler(Reuse reuse) { this.reuse=java.util.Objects.requireNonNull(reuse); }
    public record Association(SceneInputs.Key geometry,SceneInputs.Key part,long ordinal) {}
    public record SourceReference(int token,int slot) {}

    public record Work(int encodedSources,int reusedSources,int preparedAreas,int reusedAreas,boolean rebuiltLayout) {}
    public record Prepared(Compiled compiled,Work work) {}
    public static final class Compiled {
        private final LightCompiler compiler;
        private final long world;
        private final ByteBuffer sources;
        private final Layout layout;
        private final List<Entry> entries;
        private final Map<Association,SourceReference> associations;
        private Compiled(LightCompiler compiler,long world,ByteBuffer sources,Layout layout,List<Entry> entries,Map<Association,SourceReference> associations) {
            this.compiler=compiler;this.world=world;this.sources=sources.asReadOnlyBuffer();this.layout=layout;
            this.entries=List.copyOf(entries);this.associations=Map.copyOf(associations);
        }
        public ByteBuffer sources() { return sources.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
        public ByteBuffer cells() { return layout.cells.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
        public int worldCount() { return layout.worldCount; }
        public int dynamicCount() { return layout.order.size()-layout.worldCount; }
        public int attachedCount() { return layout.attachedCount; }
        public int cellCount() { return layout.cellCount; }
        public Map<Association,SourceReference> associations() { return associations; }
        public int retainedSources() { return entries.size(); }

        public long encodedBytes() { return sources.remaining()+layout.cells.remaining()+(long)entries.size()*R2Abi.SourceRecord.SIZE; }
        boolean sharesSources(Compiled before) { return this==before; }
        boolean sharesCells(Compiled before) { return before!=null && layout==before.layout; }
    }
    private record Cell(int x,int y,int z) implements Comparable<Cell> {
        @Override public int compareTo(Cell c) { int n=Integer.compare(x,c.x);if(n==0)n=Integer.compare(y,c.y);return n==0?Integer.compare(z,c.z):n; }
    }
    private record Placement(SceneInputs.Key key,Cell cell,LightInputs.Stratum stratum,int slot,int cellIndex,int ordinal) {}
    private static final class Layout {
        private final List<Placement> order;
        private final Map<SceneInputs.Key,Placement> membership;
        private final ByteBuffer cells;
        private final int worldCount,attachedCount,cellCount;
        private Layout(List<Placement> order,ByteBuffer cells,int worldCount,int attachedCount,int cellCount) {
            this.order=List.copyOf(order);this.cells=cells.asReadOnlyBuffer();this.worldCount=worldCount;
            this.attachedCount=attachedCount;this.cellCount=cellCount;
            var members=new HashMap<SceneInputs.Key,Placement>();for(var place:order)members.put(place.key(),place);membership=Map.copyOf(members);
        }
        private boolean matches(Map<SceneInputs.Key,LightInputs.Source> sources) {
            if(membership.size()!=sources.size())return false;
            for(var source:sources.values()) {
                var old=membership.get(source.key());
                if(old==null || old.stratum()!=source.stratum()
                    || source.stratum()==LightInputs.Stratum.WORLD && !old.cell().equals(cell(source.samplingOrigin())))return false;
            }
            return true;
        }
    }

    private record AreaDependency(Association association,String topology,String positions,SceneInputs.Origin meshOrigin,
                                  SceneInputs.Transform transform,SceneInputs.Origin frameOrigin) {
        private boolean matches(LightInputs.Area area,SceneCompiler.Compiled mesh,SceneInputs.Origin origin) {
            var input=mesh.input();
            return association.geometry().equals(area.geometry()) && association.part().equals(area.part()) && association.ordinal()==area.ordinal()
                && topology.equals(mesh.topologyHash()) && positions.equals(mesh.positionHash()) && meshOrigin.equals(input.origin())
                && sameTransform(transform,input.current()) && frameOrigin.equals(origin);
        }
    }
    private record AreaFacts(AreaDependency dependency,float[] center,float[] normal,float[] tangent,float area,float support,int firstTriangle,int triangleCount) {}
    private record Entry(LightInputs.Source source,Placement placement,SceneInputs.Origin origin,int geometrySlot,int token,AreaFacts area,ByteBuffer encoded) {}
    private record GeometrySlot(int slot,SceneCompiler.Compiled geometry) {}
    private int nextIdentity=1;
    private Compiled synchronous;


    public Compiled compile(List<SceneCompiler.Compiled> geometry,LightInputs.Publication lights,SceneInputs.Origin origin) {
        return compile(geometry,lights.sources(),origin);
    }
    public Compiled compile(List<SceneCompiler.Compiled> geometry,List<LightInputs.Source> lights,SceneInputs.Origin origin) {
        long world=geometry.isEmpty()?(synchronous==null?1:synchronous.world):geometry.getFirst().input().revision().world();
        var prepared=prepare(world,geometry,lights,origin,synchronous);synchronous=prepared.compiled();return synchronous;
    }




    public Prepared prepare(long world,List<SceneCompiler.Compiled> geometry,List<LightInputs.Source> lights,SceneInputs.Origin origin,Compiled predecessor) {
        if(world<=0)throw new IllegalArgumentException("Invalid source world");
        java.util.Objects.requireNonNull(origin);
        if(predecessor!=null && predecessor.compiler!=this)throw new IllegalArgumentException("Foreign source identity allocator");
        Compiled before=predecessor!=null && predecessor.world==world?predecessor:null;
        var incoming=new HashMap<SceneInputs.Key,LightInputs.Source>();boolean hasAreas=false;
        for(var source:lights) {
            if(incoming.put(source.key(),source)!=null)throw new IllegalArgumentException("Duplicate light source identity");
            hasAreas|=source instanceof LightInputs.Area;
        }
        Layout layout=reuse==Reuse.ENABLED && before!=null && before.layout.matches(incoming)?before.layout:layout(lights);
        var geometrySlots=new HashMap<SceneInputs.Key,GeometrySlot>();
        if(hasAreas)for(int i=0;i<geometry.size();i++) {
            var mesh=geometry.get(i);
            if(mesh.input().revision().world()!=world || geometrySlots.put(mesh.input().key(),new GeometrySlot(i,mesh))!=null)
                throw new IllegalArgumentException("Duplicate or foreign-world source geometry");
        }
        var entries=new ArrayList<Entry>(lights.size());var changes=new ArrayList<Entry>();
        int encoded=0,reused=0,preparedAreas=0,reusedAreas=0;
        boolean identical=before!=null && layout==before.layout,associationsChanged=!identical;
        for(var place:layout.order) {
            var source=incoming.get(place.key());var oldPlace=before==null?null:before.layout.membership.get(source.key());
            var old=oldPlace==null?null:before.entries.get(oldPlace.slot());
            int geometrySlot=-1;AreaFacts area=null;
            if(source instanceof LightInputs.Area sourceArea) {
                var found=geometrySlots.get(sourceArea.geometry());
                if(found==null)throw new IllegalArgumentException("Area source lacks its exact scene geometry");
                var mesh=found.geometry();geometrySlot=found.slot();
                if((mesh.input().participation().rayMask()&dev.rt_render_experiment.contract.Participation.VIEWMODEL)!=0)
                    throw new IllegalArgumentException("Viewmodel geometry cannot own a world light proposal; publish its semantic source separately");
                if(reuse==Reuse.ENABLED && old!=null && old.area()!=null && old.area().dependency().matches(sourceArea,mesh,origin)) {
                    area=old.area();reusedAreas++;
                } else { area=area(sourceArea,mesh,origin);preparedAreas++; }
            }
            Entry entry;
            if(reuse==Reuse.ENABLED && old!=null && old.source().equals(source) && old.placement().equals(place) && old.origin().equals(origin)
                && old.geometrySlot()==geometrySlot && old.area()==area) { entry=old;reused++; }
            else {
                int token=old==null?identity():old.token();
                var record=ByteBuffer.allocate(R2Abi.SourceRecord.SIZE).order(ByteOrder.LITTLE_ENDIAN);
                write(source,place,origin,geometrySlot,area,token,record);
                entry=new Entry(source,place,origin,geometrySlot,token,area,record.asReadOnlyBuffer());encoded++;
            }
            entries.add(entry);identical&=entry==old;
            if(entry!=old) {
                changes.add(entry);
                associationsChanged|=old==null || (old.area()==null)!=(entry.area()==null)
                    || entry.area()!=null && !entry.area().dependency().association().equals(old.area().dependency().association());
            }
        }
        var work=new Work(encoded,reused,preparedAreas,reusedAreas,before==null || layout!=before.layout);
        if(identical)return new Prepared(before,work);
        var sources=ByteBuffer.allocate(Math.max(1,lights.size())*R2Abi.SourceRecord.SIZE).order(ByteOrder.LITTLE_ENDIAN);


        boolean patch=before!=null && layout==before.layout;
        if(patch)sources.put(0,before.sources,0,before.sources.remaining());
        for(var entry:patch?changes:entries)
            sources.put(entry.placement().slot()*R2Abi.SourceRecord.SIZE,entry.encoded(),0,R2Abi.SourceRecord.SIZE);
        Map<Association,SourceReference> associations;
        if(!associationsChanged)associations=before.associations;
        else {
            associations=new LinkedHashMap<>();
            for(var entry:entries)if(entry.area()!=null && associations.put(entry.area().dependency().association(),new SourceReference(entry.token(),entry.placement().slot()))!=null)
                throw new IllegalArgumentException("Two sources claim one area primitive");
        }
        return new Prepared(new Compiled(this,world,sources,layout,entries,associations),work);
    }
    private int identity() {
        if(nextIdentity==Integer.MAX_VALUE)throw new IllegalStateException("Source identity space exhausted");return nextIdentity++;
    }
    private static Layout layout(List<LightInputs.Source> lights) {
        var world=new java.util.TreeMap<Cell,List<LightInputs.Source>>();var dynamic=new ArrayList<LightInputs.Source>();
        for(var source:lights) {
            if(source.stratum()==LightInputs.Stratum.WORLD)world.computeIfAbsent(cell(source.samplingOrigin()),ignored->new ArrayList<>()).add(source);
            else dynamic.add(source);
        }
        Comparator<LightInputs.Source> keyOrder=Comparator.comparing(LightInputs.Source::key);
        for(var sources:world.values())sources.sort(keyOrder);
        dynamic.sort(Comparator.<LightInputs.Source>comparingInt(s->s.stratum()==LightInputs.Stratum.CAMERA_ATTACHED?0:1).thenComparing(keyOrder));
        int attached=(int)dynamic.stream().filter(s->s.stratum()==LightInputs.Stratum.CAMERA_ATTACHED).count();
        if(attached>3 && dynamic.size()>4)throw new IllegalArgumentException("Attached source count leaves no dynamic proposal support");
        var cells=ByteBuffer.allocate(Math.max(1,world.size())*R2Abi.LightCellRecord.SIZE).order(ByteOrder.LITTLE_ENDIAN);
        var order=new ArrayList<Placement>();int cellIndex=0;
        for(var entry:world.entrySet()) {
            var cell=entry.getKey();int first=order.size(),ordinal=0;
            R2Abi.LightCellRecord.coordinateCount(cells,cellIndex*R2Abi.LightCellRecord.SIZE,cell.x,cell.y,cell.z,entry.getValue().size());
            R2Abi.LightCellRecord.range(cells,cellIndex*R2Abi.LightCellRecord.SIZE,first,cellIndex,0,0);
            for(var source:entry.getValue())order.add(new Placement(source.key(),cell,source.stratum(),order.size(),cellIndex,ordinal++));
            cellIndex++;
        }
        int worldCount=order.size();
        for(int i=0;i<dynamic.size();i++) { var source=dynamic.get(i);order.add(new Placement(source.key(),new Cell(0,0,0),source.stratum(),order.size(),-1,i)); }
        return new Layout(order,cells,worldCount,attached,world.size());
    }
    private static AreaFacts area(LightInputs.Area source,SceneCompiler.Compiled mesh,SceneInputs.Origin origin) {
        var part=mesh.findPart(source.part(),source.ordinal()).orElseThrow(()->new IllegalArgumentException("Area source lacks its exact prepared primitive"));
        float[] transform=GpuScene.relative(mesh.input().current(),mesh.input().origin(),origin);
        float[][] points=new float[part.vertices()][3];float[] center={0,0,0},normal={0,1,0},tangent={1,0,0};float support=0;
        var positions=mesh.positions();
        for(int i=0;i<points.length;i++) {
            int offset=(part.firstVertex()+i)*SceneCompiler.POSITION_STRIDE;
            float x=positions.getFloat(offset),y=positions.getFloat(offset+4),z=positions.getFloat(offset+8);
            for(int axis=0;axis<3;axis++) { points[i][axis]=transform[axis*4]*x+transform[axis*4+1]*y+transform[axis*4+2]*z+transform[axis*4+3];center[axis]+=points[i][axis]/points.length; }
        }
        float[] edge=subtract(points[1],points[0]),facing=cross(edge,subtract(points[2],points[0]));
        float firstArea=length(facing)*.5f,area=firstArea;
        if(points.length==4) {
            var secondEdge=subtract(points[3],points[2]);var secondFacing=cross(secondEdge,subtract(points[0],points[2]));
            float secondArea=length(secondFacing)*.5f;area+=secondArea;
            if(!(firstArea>0) && secondArea>0) { edge=secondEdge;facing=secondFacing; }
        }

        if(area>0) { tangent=unit(edge);normal=unit(facing); }
        for(var point:points)support=Math.max(support,length(subtract(point,center)));
        var dependency=new AreaDependency(new Association(source.geometry(),source.part(),source.ordinal()),mesh.topologyHash(),mesh.positionHash(),mesh.input().origin(),mesh.input().current(),origin);
        return new AreaFacts(dependency,center,normal,tangent,area,support,part.firstIndex()/3,part.indices()/3);
    }
    private static void write(LightInputs.Source source,Placement place,SceneInputs.Origin origin,int geometrySlot,AreaFacts facts,int token,ByteBuffer output) {
        float[] center={0,0,0},normal={0,1,0},tangent={1,0,0};float radius=0,area=0,support=0;int firstTriangle=0,triangleCount=0;
        if(source instanceof LightInputs.Point point) {
            center=new float[]{(float)(point.position().x()-origin.x()),(float)(point.position().y()-origin.y()),(float)(point.position().z()-origin.z())};radius=point.radius();support=radius;
        } else {
            center=facts.center();normal=facts.normal();tangent=facts.tangent();area=facts.area();support=facts.support();firstTriangle=facts.firstTriangle();triangleCount=facts.triangleCount();
        }
        R2Abi.SourceRecord.centerRadius(output,0,center[0],center[1],center[2],radius);
        R2Abi.SourceRecord.normalArea(output,0,normal[0],normal[1],normal[2],area);
        R2Abi.SourceRecord.tangentSupport(output,0,tangent[0],tangent[1],tangent[2],support);
        var emission=source.emission();var chroma=emission.measuredChroma();
        R2Abi.SourceRecord.importance(output,0,chroma.x(),chroma.y(),chroma.z(),emission.strength());
        R2Abi.SourceRecord.definition(output,0,source instanceof LightInputs.Area?R2Abi.SOURCE_QUAD:R2Abi.SOURCE_POINT,emission.sourceDefinition(),source.medium()!=0?1:0,0);
        R2Abi.SourceRecord.association(output,0,geometrySlot,firstTriangle,triangleCount,token);
        R2Abi.SourceRecord.identity(output,0,(int)source.key().high(),(int)(source.key().high()>>>32),(int)source.key().low(),(int)(source.key().low()>>>32));
        var cell=cell(source.samplingOrigin());R2Abi.SourceRecord.cell(output,0,cell.x,cell.y,cell.z,0);
        R2Abi.SourceRecord.publication(output,0,place.cellIndex(),place.ordinal(),source.stratum()==LightInputs.Stratum.CAMERA_ATTACHED?1:0,place.cellIndex()<0?1:0);
    }
    private static boolean sameTransform(SceneInputs.Transform a,SceneInputs.Transform b) {
        if(a==b)return true;
        for(int row=0;row<3;row++)for(int col=0;col<4;col++)if(Float.floatToIntBits(a.get(row,col))!=Float.floatToIntBits(b.get(row,col)))return false;
        return true;
    }
    private static Cell cell(SceneInputs.Origin p) { return new Cell((int)Math.floor(p.x()/CELL_METRES),(int)Math.floor(p.y()/CELL_METRES),(int)Math.floor(p.z()/CELL_METRES)); }
    private static float[] subtract(float[] a,float[] b) { return new float[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
    private static float[] cross(float[] a,float[] b) { return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
    private static float length(float[] v) { return (float)Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); }
    private static float[] unit(float[] v) { float length=length(v);if(!(length>0))throw new IllegalArgumentException("Degenerate emitter geometry");return new float[]{v[0]/length,v[1]/length,v[2]/length}; }
}
