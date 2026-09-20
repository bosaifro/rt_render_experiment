package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.engine.ConvexVolume.Budget;
import dev.rt_render_experiment.engine.ConvexVolume.Point;
import dev.rt_render_experiment.engine.ConvexVolume.Triangle;


final class PlanarContactCoverage {
    private final List<Point> boundary;
    private final Triangle plane;
    private final int u,v;
    PlanarContactCoverage(List<Point> boundary,Budget budget) {
        this.boundary=List.copyOf(boundary);plane=new Triangle(boundary.get(0),boundary.get(1),boundary.get(2));
        int axis=ConvexVolume.dominant(ConvexVolume.normal(plane));int a=(axis+1)%3,b=(axis+2)%3;
        if(ConvexVolume.orientation(plane.a(),plane.b(),plane.c(),a,b,budget)<0) { int swap=a;a=b;b=swap; }
        u=a;v=b;
        for(var point:boundary)if(ConvexVolume.side(plane,point,budget)!=0)throw unsupported("A contact interface must be planar");
        convex(boundary,1,budget);
    }

    boolean contains(List<Point> points,Budget budget) {
        for(var point:points) {
            if(ConvexVolume.side(plane,point,budget)!=0)return false;
            for(int edge=0;edge<boundary.size();edge++)
                if(ConvexVolume.orientation(boundary.get(edge),boundary.get((edge+1)%boundary.size()),point,u,v,budget)<0)return false;
        }
        convex(points,-1,budget);return true;
    }
    private void convex(List<Point> points,int sign,Budget budget) {
        for(int edge=0;edge<points.size();edge++) {
            var a=points.get(edge);var b=points.get((edge+1)%points.size());boolean area=false;
            for(var point:points) {
                int orientation=sign*ConvexVolume.orientation(a,b,point,u,v,budget);
                if(orientation<0)throw unsupported("Contact primitives require consistent convex opposite coverage");
                area|=orientation>0;
            }
            if(!area)throw unsupported("Contact primitive has a degenerate edge or no area");
        }
    }
    void certify(List<List<Point>> tiles,Budget budget) {
        if(tiles.isEmpty())throw unsupported("Interface has no complete opaque cover");
        var polygons=new ArrayList<List<Point>>();polygons.add(boundary);polygons.addAll(tiles);



        for(var use:PlanarEdges.cycles(polygons,u,v,budget))if(use.count()!=2 || use.direction()!=0)
            throw unsupported("Opaque contact cover has a gap, overlap or duplicate boundary");
    }
    private static IllegalArgumentException unsupported(String message) { return new IllegalArgumentException(message); }
}
