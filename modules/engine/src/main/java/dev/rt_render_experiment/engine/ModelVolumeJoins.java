package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.SceneInputs;







final class ModelVolumeJoins {
    record Model(SceneCompiler.Compiled mesh,ModelBoundary boundary,MediumInputs.Definition definition) {}
    record Region(List<Model> models) {
        Region { models=List.copyOf(models); }
        MediumInputs.Definition definition() { return models.getFirst().definition(); }
    }
    record Unmatched(Model model,OpaqueContacts.Request request) {}

    record Prepared(List<Region> regions,int interfaces,long predicates,List<Unmatched> unmatched) {}
    private record Linear(List<Float> values) {
        static Linear of(SceneInputs.Transform transform) {
            var values=new ArrayList<Float>();
            for(int r=0;r<3;r++)for(int c=0;c<3;c++) { float value=transform.get(r,c);values.add(value==0?0:value); }
            return new Linear(List.copyOf(values));
        }
    }
    private record Cell(List<ConvexVolume.Triangle> triangles,List<ConvexVolume.Point> vertices,double[] minimum,double[] maximum) {}
    private static final class Space {
        final SceneInputs.Geometry reference;
        final List<ModelInterfaceJoins.Face> hidden=new ArrayList<>();
        Space(SceneInputs.Geometry reference) { this.reference=reference; }
    }

    static Prepared prepare(List<Model> models,ConvexVolume.Budget budget) {
        var spaces=new HashMap<Linear,Space>();var cells=new HashMap<Integer,Cell>();
        int[] parents=new int[models.size()];for(int i=0;i<parents.length;i++)parents[i]=i;
        for(int i=0;i<models.size();i++) {
            var model=models.get(i);var source=model.boundary.source();if(source.occludedOrdinals().isEmpty())continue;
            var input=model.mesh.input();var space=spaces.computeIfAbsent(Linear.of(input.current()),k->new Space(input));
            var triangles=new ArrayList<ConvexVolume.Triangle>();var vertices=new java.util.LinkedHashSet<ConvexVolume.Point>();
            for(var primitive:source.surfaces()) {
                var points=new ArrayList<ConvexVolume.Point>();
                for(var corner:primitive.corners()) {
                    budget.spend(1);var p=corner.position();var point=ConvexVolume.point(space.reference,input,p.x(),p.y(),p.z());
                    points.add(point);vertices.add(point);
                }
                triangles.add(new ConvexVolume.Triangle(points.get(0),points.get(1),points.get(2)));
                if(points.size()==4)triangles.add(new ConvexVolume.Triangle(points.get(2),points.get(3),points.get(0)));
                if(source.occludedOrdinals().contains(primitive.ordinal())) {
                    if(points.size()==4 && ConvexVolume.side(triangles.get(triangles.size()-2),points.get(3),budget)!=0)
                        throw unsupported("Hidden model quad is not planar");
                    space.hidden.add(new ModelInterfaceJoins.Face(i,points));
                }
            }
            ConvexVolume.compile(space.reference,triangles,budget);
            double[] minimum={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
            double[] maximum={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            for(var point:vertices)for(int axis=0;axis<3;axis++) {
                minimum[axis]=Math.min(minimum[axis],point.axis(axis));maximum[axis]=Math.max(maximum[axis],point.axis(axis));
            }
            cells.put(i,new Cell(List.copyOf(triangles),List.copyOf(vertices),minimum,maximum));
        }
        int interfaces=0;var remaining=new ArrayList<Unmatched>();
        for(var space:spaces.values())for(var matches:ModelInterfaceJoins.components(space.hidden,budget)) {
            if(matches.size()==1) {
                var face=matches.getFirst();remaining.add(new Unmatched(models.get(face.model()),new OpaqueContacts.Request(space.reference,face.points())));
                continue;
            }
            var a=matches.getFirst();
            for(var b:matches) {
                if(!models.get(a.model()).definition.equals(models.get(b.model()).definition))throw unsupported("Hidden model interface separates different medium definitions");
                int x=root(parents,a.model()),y=root(parents,b.model());parents[Math.max(x,y)]=Math.min(x,y);
            }
            interfaces++;
        }
        var groups=new LinkedHashMap<Integer,List<Model>>();var members=new HashMap<Integer,List<Integer>>();
        for(int i=0;i<models.size();i++) {
            int root=root(parents,i);groups.computeIfAbsent(root,k->new ArrayList<>()).add(models.get(i));
            members.computeIfAbsent(root,k->new ArrayList<>()).add(i);
        }



        for(var group:members.values())for(int a=0;a<group.size();a++)for(int b=a+1;b<group.size();b++)
            if(!separated(cells.get(group.get(a)),cells.get(group.get(b)),budget))
                throw unsupported("Joined model interiors lack a separating-plane proof");
        return new Prepared(groups.values().stream().map(Region::new).toList(),interfaces,budget.used(),List.copyOf(remaining));
    }
    private static boolean separated(Cell a,Cell b,ConvexVolume.Budget budget) {
        budget.spend(3);
        for(int axis=0;axis<3;axis++)if(a.maximum[axis]<=b.minimum[axis] || b.maximum[axis]<=a.minimum[axis])return true;
        return outsidePlane(a,b,budget) || outsidePlane(b,a,budget);
    }
    private static boolean outsidePlane(Cell a,Cell b,ConvexVolume.Budget budget) {
        for(var triangle:a.triangles) {
            boolean outside=true;
            for(var point:b.vertices)if(ConvexVolume.side(triangle,point,budget)<0) { outside=false;break; }
            if(outside)return true;
        }
        return false;
    }
    private static int root(int[] parents,int id) {
        while(parents[id]!=id) { parents[id]=parents[parents[id]];id=parents[id]; }return id;
    }
    private static IllegalArgumentException unsupported(String reason) { return new IllegalArgumentException(reason); }
}
