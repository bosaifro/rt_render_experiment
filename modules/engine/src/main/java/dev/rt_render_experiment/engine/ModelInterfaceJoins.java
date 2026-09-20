package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import dev.rt_render_experiment.engine.ConvexVolume.Budget;
import dev.rt_render_experiment.engine.ConvexVolume.Point;
import dev.rt_render_experiment.engine.ConvexVolume.Triangle;


final class ModelInterfaceJoins {
    record Face(int model,List<Point> points,List<Triangle> triangles,List<Point> normals,TriangleVolume.Box box) {
        Face(int model,List<Point> points) { this(model,List.copyOf(points),triangles(points)); }
        private Face(int model,List<Point> points,List<Triangle> triangles) {
            this(model,points,triangles,triangles.stream().map(ConvexVolume::normal).toList(),box(points));
        }
        Triangle first() { return triangles.getFirst(); }
        private static List<Triangle> triangles(List<Point> points) {
            var first=new Triangle(points.get(0),points.get(1),points.get(2));
            return points.size()==3?List.of(first):List.of(first,new Triangle(points.get(2),points.get(3),points.get(0)));
        }
        private static TriangleVolume.Box box(List<Point> points) {
            var box=new TriangleVolume.Box(points.getFirst(),points.getFirst());
            for(var point:points)box=box.union(new TriangleVolume.Box(point,point));return box;
        }
    }
    static List<List<Face>> components(List<Face> faces,Budget budget) {
        if(faces.isEmpty())return List.of();
        var boxes=faces.stream().map(Face::box).toList();var index=new TriangleVolume.Index(boxes,budget);
        int[] parents=new int[faces.size()];for(int i=0;i<parents.length;i++)parents[i]=i;
        for(int a=0;a<faces.size();a++)for(int b:index.query(boxes.get(a),budget))if(b>a && faces.get(a).model!=faces.get(b).model) {
            var x=faces.get(a);var y=faces.get(b);var plane=x.first();boolean coplanar=true;
            for(var point:y.points)if(ConvexVolume.side(plane,point,budget)!=0) { coplanar=false;break; }
            if(!coplanar)continue;
            boolean overlap=false;
            for(int p=0;p<x.triangles.size();p++)for(int q=0;q<y.triangles.size();q++)
                if(TriangleVolume.crosses(x.triangles.get(p),y.triangles.get(q),x.normals.get(p),y.normals.get(q),budget))overlap=true;
            if(!overlap)continue;
            int axis=ConvexVolume.dominant(x.normals.getFirst()),u=(axis+1)%3,v=(axis+2)%3;
            if(ConvexVolume.orientation(x.points.get(0),x.points.get(1),x.points.get(2),u,v,budget)
                ==ConvexVolume.orientation(y.points.get(0),y.points.get(1),y.points.get(2),u,v,budget))
                throw new IllegalArgumentException("Overlapping hidden faces have the same orientation");
            int i=root(parents,a),j=root(parents,b);parents[Math.max(i,j)]=Math.min(i,j);
        }
        var groups=new LinkedHashMap<Integer,List<Face>>();
        for(int i=0;i<faces.size();i++)groups.computeIfAbsent(root(parents,i),k->new ArrayList<>()).add(faces.get(i));
        for(var group:groups.values())if(group.size()>1) {
            int axis=ConvexVolume.dominant(group.getFirst().normals.getFirst()),u=(axis+1)%3,v=(axis+2)%3;



            for(var use:PlanarEdges.cycles(group.stream().map(Face::points).toList(),u,v,budget))if(use.direction()!=0)
                throw new IllegalArgumentException("Hidden interface covers have a gap, overlap or unmatched extent");
        }
        return groups.values().stream().map(List::copyOf).toList();
    }
    private static int root(int[] parents,int id) {
        while(parents[id]!=id) { parents[id]=parents[parents[id]];id=parents[id]; }return id;
    }
}
