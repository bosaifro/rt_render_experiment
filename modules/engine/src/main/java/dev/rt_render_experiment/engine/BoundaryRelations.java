package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.engine.ConvexVolume.Budget;
import dev.rt_render_experiment.engine.ConvexVolume.Point;
import dev.rt_render_experiment.engine.ConvexVolume.Triangle;


final class BoundaryRelations {
    private BoundaryRelations() {}
    enum Relation { DISJOINT, A_CONTAINS_B, B_CONTAINS_A }

    static final double MINIMUM_GAP=1e-5;
    record Surface(List<Triangle> triangles,List<Point> normals,TriangleVolume.Index index,double error) {

        long logicalBytes() { return 8L+96L*triangles.size()+index.bytes(); }
    }

    static Surface prepare(SceneInputs.Geometry reference,List<Triangle> source,SceneInputs.Origin anchor,Budget budget) {
        var triangles=new ArrayList<Triangle>();double error=0;
        for(var t:source) {
            var points=new Point[3];var input=new Point[]{t.a(),t.b(),t.c()};
            for(int corner=0;corner<3;corner++) {
                budget.spend(4);double[] output=new double[3],uncertainty=new double[3];
                for(int axis=0;axis<3;axis++) {
                    var value=Interval.subtract(origin(reference.origin(),axis),origin(anchor,axis)).add(Interval.point(reference.current().get(axis,3)));
                    for(int component=0;component<3;component++)value=value.add(Interval.point(input[corner].axis(component)).multiply(reference.current().get(axis,component)));
                    output[axis]=value.midpoint();uncertainty[axis]=Math.nextUp(Math.max(output[axis]-value.lo,value.hi-output[axis]));
                }
                points[corner]=new Point(output[0],output[1],output[2]);error=Math.max(error,normUpper(new Point(uncertainty[0],uncertainty[1],uncertainty[2])));
            }
            triangles.add(new Triangle(points[0],points[1],points[2]));
        }
        var immutable=List.copyOf(triangles);var normals=immutable.stream().map(ConvexVolume::normal).toList();
        return new Surface(immutable,normals,new TriangleVolume.Index(immutable.stream().map(TriangleVolume.Box::of).toList(),budget),error);
    }
    static Relation classify(VolumeBoundary a,VolumeBoundary b,Surface first,Surface second,Budget budget) {
        double margin=Math.nextUp(Math.nextUp(first.error+second.error)+MINIMUM_GAP);
        for(int i=0;i<first.triangles.size();i++) {
            var triangle=first.triangles.get(i);
            for(int j:second.index.query(TriangleVolume.Box.of(triangle).expanded(margin),budget)) {
                if(!separated(triangle,second.triangles.get(j),first.normals.get(i),second.normals.get(j),margin,budget))
                    throw unsupported("Volume boundaries touch, cross or lack the required numerical separation");
            }
        }


        boolean bInsideA=inside(a,b.vertices().getFirst(),budget),aInsideB=inside(b,a.vertices().getFirst(),budget);
        if(bInsideA && aInsideB)throw unsupported("Contradictory volume containment");
        return bInsideA?Relation.A_CONTAINS_B:aInsideB?Relation.B_CONTAINS_A:Relation.DISJOINT;
    }
    private static boolean inside(VolumeBoundary boundary,Point witness,Budget budget) {
        budget.spend(boundary.vertices().size());var p=new SceneInputs.Origin(witness.x(),witness.y(),witness.z());
        double distance=boundary.clearance(p),uncertainty=boundary.uncertainty(p,p);
        if(!Double.isFinite(distance) || !Double.isFinite(uncertainty) || Math.abs(distance)<=uncertainty)
            throw unsupported("Volume relation witness is numerically indistinguishable from a boundary");
        return distance>0;
    }

    static boolean separated(Triangle a,Triangle b,double margin,Budget budget) {
        return separated(a,b,ConvexVolume.normal(a),ConvexVolume.normal(b),margin,budget);
    }
    private static boolean separated(Triangle a,Triangle b,Point an,Point bn,double margin,Budget budget) {
        if(axisSeparates(a,b,an,margin,budget) || axisSeparates(a,b,bn,margin,budget))return true;
        var av=new Point[]{a.a(),a.b(),a.c()};var bv=new Point[]{b.a(),b.b(),b.c()};
        for(int i=0;i<3;i++) {
            var edge=subtract(av[(i+1)%3],av[i]);
            if(axisSeparates(a,b,cross(an,edge),margin,budget))return true;
            for(int j=0;j<3;j++)if(axisSeparates(a,b,cross(edge,subtract(bv[(j+1)%3],bv[j])),margin,budget))return true;
        }
        for(int j=0;j<3;j++)if(axisSeparates(a,b,cross(bn,subtract(bv[(j+1)%3],bv[j])),margin,budget))return true;
        return false;
    }
    private static boolean axisSeparates(Triangle a,Triangle b,Point direction,double margin,Budget budget) {
        budget.spend(1);double scale=Math.max(Math.abs(direction.x()),Math.max(Math.abs(direction.y()),Math.abs(direction.z())));
        if(scale==0)return false;
        var axis=new Point(direction.x()/scale,direction.y()/scale,direction.z()/scale);
        var ap=project(a,axis,a.a(),budget);var bp=project(b,axis,a.a(),budget);
        double gap=Math.max(Math.nextDown(bp.lo-ap.hi),Math.nextDown(ap.lo-bp.hi));
        return gap>0 && Math.nextDown(gap/normUpper(axis))>margin;
    }
    private static Interval project(Triangle triangle,Point axis,Point anchor,Budget budget) {
        double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
        for(var p:new Point[]{triangle.a(),triangle.b(),triangle.c()}) {
            budget.spend(1);var value=Interval.ZERO;
            for(int i=0;i<3;i++)value=value.add(Interval.subtract(p.axis(i),anchor.axis(i)).multiply(axis.axis(i)));
            lo=Math.min(lo,value.lo);hi=Math.max(hi,value.hi);
        }
        return new Interval(lo,hi);
    }
    private static double normUpper(Point p) {
        var squared=Interval.ZERO;
        for(int i=0;i<3;i++)squared=squared.add(Interval.point(p.axis(i)).multiply(p.axis(i)));
        return Math.nextUp(Math.sqrt(squared.hi));
    }

    record Interval(double lo,double hi) {
        static final Interval ZERO=new Interval(0,0);
        Interval {
            if(!Double.isFinite(lo) || !Double.isFinite(hi) || lo>hi)throw unsupported("Unrepresentable boundary relation arithmetic");
        }
        static Interval point(double value) { return new Interval(value,value); }
        static Interval subtract(double a,double b) { return new Interval(Math.nextDown(a-b),Math.nextUp(a-b)); }
        Interval add(Interval b) { return new Interval(Math.nextDown(lo+b.lo),Math.nextUp(hi+b.hi)); }
        Interval multiply(double value) {
            if(value==0)return ZERO;
            double a=(value>0?lo:hi)*value,b=(value>0?hi:lo)*value;
            return new Interval(Math.nextDown(a),Math.nextUp(b));
        }
        double midpoint() { return lo*.5+hi*.5; }
    }
    private static double origin(SceneInputs.Origin p,int axis) { return axis==0?p.x():axis==1?p.y():p.z(); }
    private static Point subtract(Point a,Point b) { return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z()); }
    private static Point cross(Point a,Point b) { return new Point(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x()); }
    private static IllegalArgumentException unsupported(String reason) { return new IllegalArgumentException("Automatic medium classification: "+reason); }
}
