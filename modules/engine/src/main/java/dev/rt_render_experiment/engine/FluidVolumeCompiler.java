package dev.rt_render_experiment.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.FluidInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.SurfaceProperties;


final class FluidVolumeCompiler {
    enum Reason { SUPPORT, NEIGHBOR, INTERFACE, SURFACE, PRESSURE }
    static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID=1L;
        final Reason reason;
        Unsupported(Reason reason,String detail) { super(detail);this.reason=reason; }
    }
    record CellKey(SceneInputs.Key geometry,SceneInputs.Key part,long ordinal) implements Comparable<CellKey> {
        @Override public int compareTo(CellKey other) {
            int value=geometry.compareTo(other.geometry);if(value==0)value=part.compareTo(other.part);return value==0?Long.compare(ordinal,other.ordinal):value;
        }
    }
    record Face(SceneCompiler.Compiled mesh,SceneCompiler.Part part) {}
    record Region(CellKey anchor,List<Face> faces,MediumInputs.Definition definition,int cells,List<OpaqueContacts.Request> contacts) {}
    record Prepared(List<Region> regions,java.util.Set<Face> faces,int cells) {
        static final Prepared EMPTY=new Prepared(List.of(),java.util.Set.of(),0);
    }
    private record Point(double x,double y,double z) {
        Point {
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z) || x+1==x || y+1==y || z+1==z || x-1==x || y-1==y || z-1==z)
                throw unsupported(Reason.SUPPORT,"Fluid cell coordinates cannot represent unit neighbours");
            if(x==0)x=0;if(y==0)y=0;if(z==0)z=0;
        }
        Point neighbor(int face) { return new Point(x+DX[face],y+DY[face],z+DZ[face]); }
    }
    private record Node(CellKey key,Point point,SceneCompiler.Compiled mesh,FluidInputs.Cell cell,MediumInputs.Definition definition,Face[] faces) {}
    private static final int[] DX={0,0,0,0,-1,1},DY={-1,1,0,0,0,0},DZ={0,0,-1,1,0,0},OPPOSITE={1,0,3,2,5,4};
    private static final SceneInputs.Key NEUTRAL=new SceneInputs.Key(0,0);
    static Prepared prepare(List<SceneCompiler.Compiled> geometry,int maximumCells) {
        var nodes=new HashMap<Point,Node>();var sourceKeys=new HashSet<CellKey>();var allFaces=new HashSet<Face>();
        for(var mesh:geometry) {
            var input=mesh.input();if(input.fluids().isEmpty())continue;
            if(input.motion()!=SceneInputs.Motion.STATIC || input.participation().rayMask()!=Participation.WORLD || !input.participation().primaryVisible())
                throw unsupported(Reason.SUPPORT,"Fluid regions require static physical-world cell participation");
            for(int row=0;row<3;row++)for(int column=0;column<3;column++)if(input.current().get(row,column)!=(row==column?1:0))
                throw unsupported(Reason.SUPPORT,"Fluid cell lattice requires a translation-only placement");
            var manual=new HashMap<SceneInputs.Key,java.util.Set<Long>>();
            for(var primitive:input.primitives())manual.computeIfAbsent(primitive.part(),k->new HashSet<>()).add(primitive.ordinal());
            for(var cell:input.fluids()) {
                if(nodes.size()>=maximumCells)throw unsupported(Reason.PRESSURE,"Fluid cell preparation budget");
                if(cell.volume().shape()!=FluidInputs.VolumeShape.HEIGHT_FIELD_CELL)
                    throw unsupported(Reason.SUPPORT,"Fluid cell has no full height-field occupancy declaration");
                int mask=cell.volume().sameFluidFaces();
                if(((mask&2)!=0)!=cell.neighborhood().get(4).sameAbove()
                    || ((mask&4)!=0)!=cell.neighborhood().get(1).sameFluid() || ((mask&8)!=0)!=cell.neighborhood().get(7).sameFluid()
                    || ((mask&16)!=0)!=cell.neighborhood().get(3).sameFluid() || ((mask&32)!=0)!=cell.neighborhood().get(5).sameFluid())
                    throw unsupported(Reason.NEIGHBOR,"Fluid adjacency and height snapshot disagree");
                var point=new Point(input.origin().x()+input.current().get(0,3)+cell.position().x(),input.origin().y()+input.current().get(1,3)+cell.position().y(),
                    input.origin().z()+input.current().get(2,3)+cell.position().z());
                var key=new CellKey(input.key(),cell.part(),cell.ordinal());
                if(!sourceKeys.add(key))throw unsupported(Reason.SUPPORT,"Repeated fluid source identity");
                var definition=definition(cell.still());
                if(!definition.equals(definition(cell.flowing())) || !definition.equals(definition(cell.overlay())))
                    throw unsupported(Reason.SURFACE,"Fluid surface roles imply different interior media");
                var faces=new Face[6];
                for(int direction=0;direction<6;direction++) {
                    long ordinal=Math.addExact(Math.multiplyExact(cell.ordinal(),6),direction);
                    if(manual.getOrDefault(cell.part(),java.util.Set.of()).contains(ordinal))
                        throw unsupported(Reason.SUPPORT,"Manual primitive aliases a reserved fluid face identity");
                    var part=mesh.findPart(cell.part(),ordinal);
                    if(part.isPresent()) {
                        if(!definition.equals(definition(part.orElseThrow().surface())))throw unsupported(Reason.SURFACE,"Rendered fluid face disagrees with its medium");
                        faces[direction]=new Face(mesh,part.orElseThrow());allFaces.add(faces[direction]);
                    }
                }
                if(nodes.put(point,new Node(key,point,mesh,cell,definition,faces))!=null)throw unsupported(Reason.SUPPORT,"Overlapping fluid source cells");
            }
        }
        if(nodes.isEmpty())return Prepared.EMPTY;


        for(var node:nodes.values())for(int direction=0;direction<6;direction++) {
            if(!connected(node,direction))continue;
            var adjacent=nodes.get(node.point.neighbor(direction));
            if(adjacent==null || !connected(adjacent,OPPOSITE[direction]))throw unsupported(Reason.NEIGHBOR,"Fluid region has a missing or inconsistent same-fluid neighbour");
            if(!node.definition.equals(adjacent.definition))throw unsupported(Reason.INTERFACE,"Connected fluid cells have different medium definitions");
            if(node.faces[direction]!=null || adjacent.faces[OPPOSITE[direction]]!=null)
                throw unsupported(Reason.INTERFACE,"A same-fluid connection retains a rendered internal interface");
        }
        var ordered=nodes.values().stream().sorted(java.util.Comparator.comparing(Node::key)).toList();
        var visited=new HashSet<CellKey>();var regions=new ArrayList<Region>();
        for(var start:ordered)if(visited.add(start.key)) {
            var queue=new ArrayDeque<Node>();queue.add(start);var faces=new ArrayList<Face>();var contacts=new ArrayList<OpaqueContacts.Request>();int cells=0;
            while(!queue.isEmpty()) {
                var node=queue.removeFirst();cells++;int missing=0;
                for(int direction=0;direction<6;direction++) {
                    if(node.faces[direction]!=null)faces.add(node.faces[direction]);
                    if(connected(node,direction)) {
                        var next=nodes.get(node.point.neighbor(direction));if(visited.add(next.key))queue.add(next);
                    } else if(node.faces[direction]==null)missing|=1<<direction;
                }



                if(missing!=0)for(var primitive:new FluidCompiler().interfaces(node.cell,missing)) {
                    var points=primitive.corners().stream().map(c->new ConvexVolume.Point(c.position().x(),c.position().y(),c.position().z())).toList();
                    contacts.add(new OpaqueContacts.Request(node.mesh.input(),points));
                }
            }
            if(faces.isEmpty() && contacts.isEmpty())throw unsupported(Reason.INTERFACE,"Fluid region has no complete boundary");
            regions.add(new Region(start.key,List.copyOf(faces),start.definition,cells,List.copyOf(contacts)));
        }
        return new Prepared(List.copyOf(regions),java.util.Set.copyOf(allFaces),nodes.size());
    }
    private static boolean connected(Node node,int face) { return (node.cell.volume().sameFluidFaces()&(1<<face))!=0; }
    private static MediumInputs.Definition definition(SceneInputs.Surface surface) {
        if(surface.boundary()!=SceneInputs.Boundary.UNQUALIFIED || surface.coverage()!=SceneInputs.Coverage.DIELECTRIC || !surface.doubleSided()
            || surface.layer()!=0 || surface.layerSeparation()!=0 || (surface.properties()&(SurfaceProperties.THIN|SurfaceProperties.TRANSLUCENT))!=0
            || surface.layers().modelResponse() || !surface.layers().overlay().equals(NEUTRAL) || surface.layers().emission()==MaterialInputs.Emission.MERGED
            || MediumProfiles.kind(surface.material(),surface.properties())!=MediumInputs.Kind.WATER)
            throw unsupported(Reason.SURFACE,"Fluid surface lacks a supported homogeneous full-volume response");


        return MediumProfiles.definition(MediumInputs.Kind.WATER,new SceneInputs.Vec3(1,1,1));
    }
    private static Unsupported unsupported(Reason reason,String message) { return new Unsupported(reason,message); }
}
