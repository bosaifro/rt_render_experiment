package dev.rt_render_experiment.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import dev.rt_render_experiment.engine.ConvexVolume.Budget;
import dev.rt_render_experiment.engine.ConvexVolume.Point;
import dev.rt_render_experiment.engine.ConvexVolume.Triangle;


final class ExactContactCover {
    private record Fraction(BigInteger n,BigInteger d) implements Comparable<Fraction> {
        private static final Fraction ZERO=new Fraction(BigInteger.ZERO,BigInteger.ONE);
        Fraction {
            if(d.signum()==0)throw new IllegalArgumentException("Singular contact construction");
            if(d.signum()<0) { n=n.negate();d=d.negate(); }
            var common=n.gcd(d);n=n.divide(common);d=d.divide(common);
        }
        static Fraction of(double value) {
            var exact=new BigDecimal(value);int scale=exact.scale();
            return scale>=0?new Fraction(exact.unscaledValue(),BigInteger.TEN.pow(scale))
                :new Fraction(exact.unscaledValue().multiply(BigInteger.TEN.pow(-scale)),BigInteger.ONE);
        }
        Fraction add(Fraction b) { return new Fraction(n.multiply(b.d).add(b.n.multiply(d)),d.multiply(b.d)); }
        Fraction subtract(Fraction b) { return new Fraction(n.multiply(b.d).subtract(b.n.multiply(d)),d.multiply(b.d)); }
        Fraction multiply(Fraction b) { return new Fraction(n.multiply(b.n),d.multiply(b.d)); }
        Fraction divide(Fraction b) { return new Fraction(n.multiply(b.d),d.multiply(b.n)); }
        int sign() { return n.signum(); }
        @Override public int compareTo(Fraction b) { return n.multiply(b.d).compareTo(b.n.multiply(d)); }
    }
    private record Vertex(Fraction x,Fraction y,Fraction z) {
        static Vertex of(Point point) { return new Vertex(Fraction.of(point.x()),Fraction.of(point.y()),Fraction.of(point.z())); }
        Fraction axis(int axis) { return axis==0?x:axis==1?y:z; }
        Vertex interpolate(Vertex b,Fraction t) {
            return new Vertex(x.add(b.x.subtract(x).multiply(t)),y.add(b.y.subtract(y).multiply(t)),z.add(b.z.subtract(z).multiply(t)));
        }
    }
    record Polygon(List<Vertex> vertices) { Polygon { vertices=List.copyOf(vertices); } }
    private record Edge(Vertex a,Vertex b) {}
    private static final class Use { int count,direction; }
    private final Triangle plane;
    private final List<Vertex> boundary;
    private final int u,v;
    ExactContactCover(List<Point> points,Budget budget) {
        new PlanarContactCoverage(points,budget);plane=new Triangle(points.get(0),points.get(1),points.get(2));
        int axis=ConvexVolume.dominant(ConvexVolume.normal(plane)),a=(axis+1)%3,b=(axis+2)%3;
        if(ConvexVolume.orientation(plane.a(),plane.b(),plane.c(),a,b,budget)<0) { int swap=a;a=b;b=swap; }
        u=a;v=b;boundary=points.stream().map(Vertex::of).toList();
    }
    static Polygon whole(List<Point> points) { return new Polygon(points.stream().map(Vertex::of).toList()); }

    Polygon intersection(List<Point> points,Budget budget) {
        for(var point:points)if(ConvexVolume.side(plane,point,budget)!=0)return null;
        new PlanarContactCoverage(points,budget);
        List<Vertex> clipped=points.stream().map(Vertex::of).toList();
        for(int edge=0;edge<boundary.size() && !clipped.isEmpty();edge++) {
            var a=boundary.get(edge);var b=boundary.get((edge+1)%boundary.size());var output=new ArrayList<Vertex>();
            var previous=clipped.getLast();var before=side(a,b,previous,budget);
            for(var current:clipped) {
                var after=side(a,b,current,budget);
                if((before.sign()>=0)!=(after.sign()>=0))append(output,previous.interpolate(current,before.divide(before.subtract(after))));
                if(after.sign()>=0)append(output,current);
                previous=current;before=after;
            }
            if(output.size()>1 && output.getFirst().equals(output.getLast()))output.removeLast();clipped=output;
        }
        if(clipped.size()<3)return null;
        var area=Fraction.ZERO;
        for(int i=0;i<clipped.size();i++) {
            budget.spend(1);var a=clipped.get(i);var b=clipped.get((i+1)%clipped.size());
            area=area.add(a.axis(u).multiply(b.axis(v)).subtract(b.axis(u).multiply(a.axis(v))));
        }
        if(area.sign()==0)return null;
        if(area.sign()>0)throw new IllegalArgumentException("Crossing contact cover has the wrong orientation");
        return new Polygon(clipped);
    }
    void certify(List<Polygon> pieces,Budget budget) {
        if(pieces.isEmpty())throw new IllegalArgumentException("Crossing contact has no opaque cover");
        var polygons=new ArrayList<Polygon>();polygons.add(new Polygon(boundary));polygons.addAll(pieces);
        var vertices=new LinkedHashSet<Vertex>();var edges=new ArrayList<Edge>();
        for(var polygon:polygons) {
            var points=polygon.vertices;vertices.addAll(points);
            for(int i=0;i<points.size();i++)edges.add(new Edge(points.get(i),points.get((i+1)%points.size())));
        }
        var uses=new HashMap<Edge,Use>();
        for(var edge:edges) {
            if(edge.a.equals(edge.b))throw new IllegalArgumentException("Degenerate exact contact edge");
            int axis=edge.a.axis(u).equals(edge.b.axis(u))?v:u;
            boolean forward=edge.a.axis(axis).compareTo(edge.b.axis(axis))<0;
            var lo=(forward?edge.a:edge.b).axis(axis);var hi=(forward?edge.b:edge.a).axis(axis);var split=new ArrayList<Vertex>();
            for(var point:vertices) {
                budget.spend(1);if(point.axis(axis).compareTo(lo)<0 || point.axis(axis).compareTo(hi)>0)continue;
                if(side(edge.a,edge.b,point,budget).sign()==0)split.add(point);
            }
            split.sort(Comparator.comparing(p->p.axis(axis)));
            for(int i=1;i<split.size();i++) {
                var a=split.get(forward?i-1:i);var b=split.get(forward?i:i-1);int direction=compare(a,b);
                var key=direction<0?new Edge(a,b):new Edge(b,a);var use=uses.computeIfAbsent(key,k->new Use());
                use.count++;use.direction+=Integer.signum(direction);
            }
        }
        for(var use:uses.values())if(use.count!=2 || use.direction!=0)
            throw new IllegalArgumentException("Crossing opaque cover has a gap, overlap or duplicate");
    }
    private Fraction side(Vertex a,Vertex b,Vertex p,Budget budget) {
        budget.spend(1);
        return b.axis(u).subtract(a.axis(u)).multiply(p.axis(v).subtract(a.axis(v)))
            .subtract(b.axis(v).subtract(a.axis(v)).multiply(p.axis(u).subtract(a.axis(u))));
    }
    private static void append(List<Vertex> points,Vertex value) { if(points.isEmpty() || !points.getLast().equals(value))points.add(value); }
    private static int compare(Vertex a,Vertex b) { for(int axis=0;axis<3;axis++) { int value=a.axis(axis).compareTo(b.axis(axis));if(value!=0)return value; }return 0; }
}
