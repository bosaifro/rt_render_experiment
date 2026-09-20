package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.engine.ConvexVolume.Budget;
import dev.rt_render_experiment.engine.ConvexVolume.Point;


final class PlanarEdges {
    record Segment(Point from,Point to) {}
    record Use(Segment canonical,int count,int direction) {
        Segment oriented() { return direction<0?canonical:new Segment(canonical.to,canonical.from); }
    }
    private static final class MutableUse { int count,direction; }
    static List<Use> cycles(List<List<Point>> polygons,int u,int v,Budget budget) {
        var segments=new ArrayList<Segment>();var vertices=new LinkedHashSet<Point>();
        for(var points:polygons) {
            vertices.addAll(points);
            for(int i=0;i<points.size();i++)segments.add(new Segment(points.get(i),points.get((i+1)%points.size())));
        }
        return split(segments,vertices,u,v,budget);
    }
    static List<Use> split(List<Segment> segments,Set<Point> vertices,int u,int v,Budget budget) {
        var uses=new HashMap<Segment,MutableUse>();
        for(var segment:segments) {
            var a=segment.from;var b=segment.to;
            if(a.equals(b))throw new IllegalArgumentException("Zero-length planar certificate edge");
            int axis=ConvexVolume.dominant(new Point(b.x()-a.x(),b.y()-a.y(),b.z()-a.z()));
            double lo=Math.min(a.axis(axis),b.axis(axis)),hi=Math.max(a.axis(axis),b.axis(axis));var split=new ArrayList<Point>();
            for(var point:vertices) {
                budget.spend(1);if(point.axis(axis)<lo || point.axis(axis)>hi)continue;
                if(ConvexVolume.orientation(a,b,point,u,v,budget)==0)split.add(point);
            }
            split.sort(Comparator.comparingDouble(p->p.axis(axis)));boolean forward=a.axis(axis)<b.axis(axis);
            for(int i=1;i<split.size();i++) {
                var from=split.get(forward?i-1:i);var to=split.get(forward?i:i-1);int direction=ConvexVolume.compare(from,to);
                if(direction==0)throw new IllegalArgumentException("Zero-length planar certificate subdivision");
                var key=direction<0?new Segment(from,to):new Segment(to,from);var use=uses.computeIfAbsent(key,k->new MutableUse());
                use.count++;use.direction+=Integer.signum(direction);
            }
        }
        return uses.entrySet().stream().map(e->new Use(e.getKey(),e.getValue().count,e.getValue().direction)).toList();
    }
}
