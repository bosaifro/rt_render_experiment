package dev.rt_render_experiment.engine;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.engine.ConvexVolume.Budget;
import dev.rt_render_experiment.engine.ConvexVolume.Point;
import dev.rt_render_experiment.engine.ConvexVolume.Triangle;






final class TriangleVolume implements VolumeBoundary {
    private final SceneInputs.Origin origin;
    private final SceneInputs.Transform transform;
    private final double[][] inverse;
    private final double condition;
    private final List<Triangle> local,metric;
    private final List<Point> metricNormals;
    private final Box worldBounds;
    private final List<Point> worldVertices;
    private final Index localIndex,metricIndex;

    static TriangleVolume compile(SceneInputs.Geometry reference,List<Triangle> triangles,Budget budget) {
        return new TriangleVolume(reference,triangles,budget);
    }
    private TriangleVolume(SceneInputs.Geometry reference,List<Triangle> triangles,Budget budget) {
        origin=reference.origin();transform=reference.current();inverse=ConvexVolume.inverse(transform);condition=ConvexVolume.condition(transform,inverse);
        if(!Double.isFinite(condition) || condition>1e6)throw unsupported("Ill-conditioned volume reference");
        if(triangles.size()<4)throw unsupported("Volume has no closed three-dimensional extent");
        local=List.copyOf(triangles);
        var normals=new ArrayList<Point>();var points=new ArrayList<Point>();var identifiers=new HashMap<Point,Integer>();
        for(var t:local) {
            budget.spend(1);normals.add(ConvexVolume.normal(t));
            for(var p:points(t))if(!identifiers.containsKey(p)) { identifiers.put(p,points.size());points.add(p); }
        }
        localIndex=new Index(local.stream().map(Box::of).toList(),budget);
        close(points,identifiers,budget);
        for(int i=0;i<local.size();i++)for(int j:localIndex.query(Box.of(local.get(i)),budget))if(j>i) {
            budget.spend(1);
            if(crosses(local.get(i),local.get(j),normals.get(i),normals.get(j),budget))throw unsupported("Self-intersecting or overlapping triangle boundary");
        }


        var anchor=local.getFirst().a();BigDecimal volume=BigDecimal.ZERO;
        for(var t:local) {
            budget.spend(1);
            volume=volume.add(component(t,1,2).multiply(exact(t.a().x()).subtract(exact(anchor.x()))))
                .add(component(t,2,0).multiply(exact(t.a().y()).subtract(exact(anchor.y()))))
                .add(component(t,0,1).multiply(exact(t.a().z()).subtract(exact(anchor.z()))));
        }
        if(volume.signum()<=0)throw unsupported("Inward or zero-volume triangle boundary");
        metric=local.stream().map(t->new Triangle(metric(t.a()),metric(t.b()),metric(t.c()))).toList();
        metricNormals=metric.stream().map(ConvexVolume::normal).toList();
        metricIndex=new Index(metric.stream().map(Box::of).toList(),budget);
        worldVertices=points.stream().map(this::metric).map(p->new Point(p.x()+origin.x()+transform.get(0,3),
            p.y()+origin.y()+transform.get(1,3),p.z()+origin.z()+transform.get(2,3))).toList();
        Box world=new Box(worldVertices.getFirst(),worldVertices.getFirst());for(var p:worldVertices)world=world.union(new Box(p,p));worldBounds=world;
    }
    private record Edge(int a,int b) {}
    private static final class Use { int count,direction,first=-1,second=-1; }
    private void close(List<Point> points,HashMap<Point,Integer> identifiers,Budget budget) {
        var pointIndex=new Index(points.stream().map(p->new Box(p,p)).toList(),budget);
        var edges=new HashMap<Edge,Use>();
        for(int face=0;face<local.size();face++) {
            var triangle=points(local.get(face));
            for(int edge=0;edge<3;edge++) {
                var a=triangle[edge];var b=triangle[(edge+1)%3];int axis=ConvexVolume.dominant(subtract(b,a));
                var split=new ArrayList<Point>();
                for(int index:pointIndex.query(Box.of(a,b),budget)) {
                    var p=points.get(index);
                    if(onSegment(a,b,p,budget))split.add(p);
                }
                split.sort(Comparator.comparingDouble(p->p.axis(axis)));
                boolean forward=a.axis(axis)<b.axis(axis);
                for(int i=1;i<split.size();i++) {
                    int from=identifiers.get(split.get(forward?i-1:i)),to=identifiers.get(split.get(forward?i:i-1));
                    var key=new Edge(Math.min(from,to),Math.max(from,to));var use=edges.computeIfAbsent(key,k->new Use());
                    use.count++;use.direction+=Integer.compare(from,to);
                    if(use.first<0)use.first=face;else use.second=face;
                }
            }
        }
        var adjacency=new ArrayList<HashSet<Integer>>();for(int i=0;i<local.size();i++)adjacency.add(new HashSet<>());
        var links=new HashMap<Integer,HashMap<Integer,HashSet<Integer>>>();
        for(var entry:edges.entrySet()) {
            budget.spend(1);var edge=entry.getKey();var use=entry.getValue();
            if(use.count!=2 || use.direction!=0 || use.first==use.second)throw unsupported("Open, duplicate or inconsistently oriented triangle boundary");
            adjacency.get(use.first).add(use.second);adjacency.get(use.second).add(use.first);
            for(int vertex:new int[]{edge.a,edge.b}) {
                var link=links.computeIfAbsent(vertex,k->new HashMap<>());
                link.computeIfAbsent(use.first,k->new HashSet<>()).add(use.second);
                link.computeIfAbsent(use.second,k->new HashSet<>()).add(use.first);
            }
        }
        if(visit(0,adjacency::get,budget)!=local.size())throw unsupported("Disconnected triangle boundary");
        for(var link:links.values()) {
            for(var neighbors:link.values())if(neighbors.size()!=2)throw unsupported("Non-manifold triangle vertex");
            if(visit(link.keySet().iterator().next(),link::get,budget)!=link.size())throw unsupported("Pinched triangle vertex");
        }
    }
    private static int visit(int start,java.util.function.IntFunction<HashSet<Integer>> neighbors,Budget budget) {
        var visited=new HashSet<Integer>();var queue=new ArrayDeque<Integer>();visited.add(start);queue.add(start);
        while(!queue.isEmpty())for(int next:neighbors.apply(queue.removeFirst())) { budget.spend(1);if(visited.add(next))queue.add(next); }
        return visited.size();
    }

    static boolean crosses(Triangle a,Triangle b,Budget budget) {
        return crosses(a,b,ConvexVolume.normal(a),ConvexVolume.normal(b),budget);
    }

    static boolean crosses(Triangle a,Triangle b,Point an,Point bn,Budget budget) {
        var ap=points(a);var bp=points(b);int[] sides=new int[3];
        for(int i=0;i<3;i++)sides[i]=ConvexVolume.side(a,bp[i],budget);
        if(strictSide(sides))return false;
        if(sides[0]==0 && sides[1]==0 && sides[2]==0) {
            int axis=ConvexVolume.dominant(an),u=(axis+1)%3,v=(axis+2)%3;
            return !separates2d(ap,bp,u,v,budget) && !separates2d(bp,ap,u,v,budget);
        }
        for(int i=0;i<3;i++)sides[i]=ConvexVolume.side(b,ap[i],budget);
        if(strictSide(sides))return false;
        for(int i=0;i<3;i++)if(crosses(ap[i],ap[(i+1)%3],b,bn,budget) || crosses(bp[i],bp[(i+1)%3],a,an,budget))return true;
        return false;
    }
    private static boolean strictSide(int[] signs) { return signs[0]>0 && signs[1]>0 && signs[2]>0 || signs[0]<0 && signs[1]<0 && signs[2]<0; }
    private static boolean separates2d(Point[] a,Point[] b,int u,int v,Budget budget) {
        int direction=ConvexVolume.orientation(a[0],a[1],a[2],u,v,budget);
        for(int i=0;i<3;i++) {
            boolean outside=true;
            for(var p:b)if(direction*ConvexVolume.orientation(a[i],a[(i+1)%3],p,u,v,budget)>0) { outside=false;break; }
            if(outside)return true;
        }
        return false;
    }
    private static boolean crosses(Point p,Point q,Triangle triangle,Point normal,Budget budget) {
        int a=ConvexVolume.side(triangle,p,budget),b=ConvexVolume.side(triangle,q,budget);
        if(a!=0 && a==b)return false;
        int axis=ConvexVolume.dominant(normal),u=(axis+1)%3,v=(axis+2)%3;
        if(a==0 && b==0)return segmentInterior(p,q,triangle,u,v,budget);
        if(a==0 || b==0) {
            var point=a==0?p:q;var vertices=points(triangle);int direction=ConvexVolume.orientation(vertices[0],vertices[1],vertices[2],u,v,budget);

            for(int i=0;i<3;i++)if(direction*ConvexVolume.orientation(vertices[i],vertices[(i+1)%3],point,u,v,budget)<=0)return false;
            return true;
        }
        var vertices=points(triangle);int positive=0,negative=0,zero=0;
        for(int i=0;i<3;i++) {
            int sign=ConvexVolume.side(new Triangle(p,q,vertices[i]),vertices[(i+1)%3],budget);
            if(sign>0)positive++;else if(sign<0)negative++;else zero++;
        }
        if(positive>0 && negative>0)return false;
        if(zero>=2)for(var vertex:vertices)if(onSegment(p,q,vertex,budget))return false;
        return true;
    }
    private record Fraction(BigDecimal numerator,BigDecimal denominator) implements Comparable<Fraction> {
        @Override public int compareTo(Fraction other) { return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator)); }
    }
    private static boolean segmentInterior(Point p,Point q,Triangle triangle,int u,int v,Budget budget) {
        var vertices=points(triangle);int sign=ConvexVolume.orientation(vertices[0],vertices[1],vertices[2],u,v,budget);
        var lo=new Fraction(BigDecimal.ZERO,BigDecimal.ONE);var hi=new Fraction(BigDecimal.ONE,BigDecimal.ONE);
        for(int i=0;i<3;i++) {
            budget.spend(1);var a=orient2d(vertices[i],vertices[(i+1)%3],p,u,v).multiply(BigDecimal.valueOf(sign));
            var b=orient2d(vertices[i],vertices[(i+1)%3],q,u,v).multiply(BigDecimal.valueOf(sign));
            if(a.signum()<=0 && b.signum()<=0)return false;
            if(a.signum()<=0) { var next=new Fraction(a.negate(),b.subtract(a));if(next.compareTo(lo)>0)lo=next; }
            if(b.signum()<=0) { var next=new Fraction(a,a.subtract(b));if(next.compareTo(hi)<0)hi=next; }
        }
        return lo.compareTo(hi)<0;
    }
    private static boolean onSegment(Point a,Point b,Point p,Budget budget) {
        if(!Box.of(a,b).contains(p))return false;
        return ConvexVolume.orientation(a,b,p,0,1,budget)==0 && ConvexVolume.orientation(a,b,p,1,2,budget)==0 && ConvexVolume.orientation(a,b,p,2,0,budget)==0;
    }
    @Override public double clearance(SceneInputs.Origin eye) {
        var p=new Point(eye.x()-origin.x()-transform.get(0,3),eye.y()-origin.y()-transform.get(1,3),eye.z()-origin.z()-transform.get(2,3));
        double distance=Math.sqrt(metricIndex.nearest(p,metric,metricNormals));
        if(!Double.isFinite(distance))throw unsupported("Unrepresentable triangle boundary distance");
        if(distance==0)return 0;
        double[] values={p.x(),p.y(),p.z()};
        var localPoint=new Point(dot(inverse[0],values),dot(inverse[1],values),dot(inverse[2],values));
        int winding=0;var budget=new Budget(Long.MAX_VALUE);var box=localIndex.root.box;
        if(localPoint.x()<=box.hi.x())for(int index:localIndex.query(new Box(localPoint,new Point(box.hi.x(),localPoint.y(),localPoint.z())),budget)) {
            var t=local.get(index);int sign=ConvexVolume.orientation(t.a(),t.b(),t.c(),1,2,budget);
            if(sign==0 || ConvexVolume.side(t,localPoint,budget)*sign>=0)continue;
            var vertices=sign>0?points(t):new Point[]{t.a(),t.c(),t.b()};boolean covered=true;
            for(int edge=0;edge<3;edge++) {
                var a=vertices[edge];var b=vertices[(edge+1)%3];int side=ConvexVolume.orientation(a,b,localPoint,1,2,budget);
                boolean inclusive=b.z()>a.z() || b.z()==a.z() && b.y()<a.y();
                if(side<0 || side==0 && !inclusive) { covered=false;break; }
            }
            if(covered)winding+=sign;
        }
        if(winding!=0 && winding!=1)throw unsupported("Ambiguous triangle-boundary winding");
        return winding==1?distance:-distance;
    }
    @Override public double uncertainty(SceneInputs.Origin eye,SceneInputs.Origin frameOrigin) {
        double magnitude=1;
        double[] frame={frameOrigin.x(),frameOrigin.y(),frameOrigin.z()};
        for(int axis=0;axis<3;axis++)magnitude=Math.max(magnitude,Math.max(Math.abs(worldBounds.lo.axis(axis)-frame[axis]),Math.abs(worldBounds.hi.axis(axis)-frame[axis])));
        double world=Math.max(1,Math.max(Math.abs(eye.x()),Math.max(Math.abs(eye.y()),Math.abs(eye.z()))));
        return 1e-5+8*Math.ulp((float)magnitude)+32*condition*Math.ulp(world);
    }
    @Override public List<Point> vertices() { return worldVertices; }
    @Override public double condition() { return condition; }
    @Override public long logicalBytes() { return 200L+168L*local.size()+24L*worldVertices.size()+localIndex.bytes()+metricIndex.bytes(); }
    private Point metric(Point p) {
        return new Point(transform.get(0,0)*p.x()+transform.get(0,1)*p.y()+transform.get(0,2)*p.z(),
            transform.get(1,0)*p.x()+transform.get(1,1)*p.y()+transform.get(1,2)*p.z(),
            transform.get(2,0)*p.x()+transform.get(2,1)*p.y()+transform.get(2,2)*p.z());
    }
    private static double distanceSquared(Point p,Triangle t,Point n) {
        var vertices=points(t);double d=dot(n,subtract(p,t.a())),n2=dot(n,n);
        double scale=Math.max(1,Math.max(length(subtract(p,t.a())),Math.max(length(subtract(t.b(),t.a())),length(subtract(t.c(),t.a())))));
        boolean projectedInside=true;
        for(int i=0;i<3;i++) {
            var edge=subtract(vertices[(i+1)%3],vertices[i]);var inward=cross(n,edge);
            if(dot(inward,subtract(p,vertices[i])) < -length(inward)*scale*1e-12) { projectedInside=false;break; }
        }
        if(projectedInside)return Math.max(0,d*d/n2);
        double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<3;i++) {
            var a=vertices[i];var ab=subtract(vertices[(i+1)%3],a);double fraction=Math.clamp(dot(subtract(p,a),ab)/dot(ab,ab),0,1);
            var delta=new Point(p.x()-a.x()-fraction*ab.x(),p.y()-a.y()-fraction*ab.y(),p.z()-a.z()-fraction*ab.z());best=Math.min(best,dot(delta,delta));
        }
        return best;
    }
    record Box(Point lo,Point hi) {
        static Box of(Point a,Point b) { return new Box(new Point(Math.min(a.x(),b.x()),Math.min(a.y(),b.y()),Math.min(a.z(),b.z())),new Point(Math.max(a.x(),b.x()),Math.max(a.y(),b.y()),Math.max(a.z(),b.z()))); }
        static Box of(Triangle t) { return of(t.a(),t.b()).union(new Box(t.c(),t.c())); }
        Box union(Box b) { return new Box(of(lo,b.lo).lo,of(hi,b.hi).hi); }
        Box expanded(double distance) {
            return new Box(new Point(Math.nextDown(lo.x()-distance),Math.nextDown(lo.y()-distance),Math.nextDown(lo.z()-distance)),
                new Point(Math.nextUp(hi.x()+distance),Math.nextUp(hi.y()+distance),Math.nextUp(hi.z()+distance)));
        }
        boolean contains(Point p) { return p.x()>=lo.x() && p.x()<=hi.x() && p.y()>=lo.y() && p.y()<=hi.y() && p.z()>=lo.z() && p.z()<=hi.z(); }
        boolean overlaps(Box b) { return lo.x()<=b.hi.x() && b.lo.x()<=hi.x() && lo.y()<=b.hi.y() && b.lo.y()<=hi.y() && lo.z()<=b.hi.z() && b.lo.z()<=hi.z(); }
        double distanceSquared(Point p) {
            double distance=0;for(int i=0;i<3;i++) { double delta=Math.max(Math.max(lo.axis(i)-p.axis(i),p.axis(i)-hi.axis(i)),0);distance+=delta*delta; }return distance;
        }
        double center(int axis) { return lo.axis(axis)*.5+hi.axis(axis)*.5; }
    }
    static final class Index {
        private record Node(Box box,Node left,Node right,int from,int to) {}
        private final List<Box> boxes;
        private final Integer[] order;
        private final Node root;
        private int nodes;
        Index(List<Box> boxes,Budget budget) {
            this.boxes=List.copyOf(boxes);order=new Integer[boxes.size()];for(int i=0;i<order.length;i++)order[i]=i;
            root=build(0,order.length,budget);
        }
        private Node build(int from,int to,Budget budget) {
            budget.spend(to-from);nodes++;Box bounds=boxes.get(order[from]);for(int i=from+1;i<to;i++)bounds=bounds.union(boxes.get(order[i]));
            if(to-from<=8)return new Node(bounds,null,null,from,to);
            int axis=ConvexVolume.dominant(subtract(bounds.hi,bounds.lo));
            java.util.Arrays.sort(order,from,to,Comparator.<Integer>comparingDouble(i->boxes.get(i).center(axis)).thenComparingInt(i->i));
            int mid=(from+to)>>>1;return new Node(bounds,build(from,mid,budget),build(mid,to,budget),from,to);
        }
        List<Integer> query(Box box,Budget budget) { var result=new ArrayList<Integer>();query(root,box,budget,result);return result; }
        private void query(Node node,Box box,Budget budget,List<Integer> result) {
            budget.spend(1);if(!node.box.overlaps(box))return;
            if(node.left==null) { for(int i=node.from;i<node.to;i++) { budget.spend(1);if(boxes.get(order[i]).overlaps(box))result.add(order[i]); } }
            else { query(node.left,box,budget,result);query(node.right,box,budget,result); }
        }
        double nearest(Point p,List<Triangle> triangles,List<Point> normals) { return nearest(root,p,triangles,normals,Double.POSITIVE_INFINITY); }
        private double nearest(Node node,Point p,List<Triangle> triangles,List<Point> normals,double best) {
            if(node.box.distanceSquared(p)>best)return best;
            if(node.left==null) { for(int i=node.from;i<node.to;i++)best=Math.min(best,TriangleVolume.distanceSquared(p,triangles.get(order[i]),normals.get(order[i])));return best; }
            Node a=node.left,b=node.right;if(a.box.distanceSquared(p)>b.box.distanceSquared(p)) { var swap=a;a=b;b=swap; }
            return nearest(b,p,triangles,normals,nearest(a,p,triangles,normals,best));
        }
        long bytes() { return 48L*boxes.size()+4L*order.length+64L*nodes; }
    }
    private static Point[] points(Triangle t) { return new Point[]{t.a(),t.b(),t.c()}; }
    private static Point subtract(Point a,Point b) { return new Point(a.x()-b.x(),a.y()-b.y(),a.z()-b.z()); }
    private static Point cross(Point a,Point b) { return new Point(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x()); }
    private static double dot(Point a,Point b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    private static double dot(double[] a,double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static double length(Point p) { return Math.hypot(Math.hypot(p.x(),p.y()),p.z()); }
    private static BigDecimal exact(double value) { return new BigDecimal(value); }
    private static BigDecimal component(Triangle t,int u,int v) { return orient2d(t.a(),t.b(),t.c(),u,v); }
    private static BigDecimal orient2d(Point a,Point b,Point c,int u,int v) {
        return exact(b.axis(u)).subtract(exact(a.axis(u))).multiply(exact(c.axis(v)).subtract(exact(a.axis(v))))
            .subtract(exact(b.axis(v)).subtract(exact(a.axis(v))).multiply(exact(c.axis(u)).subtract(exact(a.axis(u)))));
    }
    private static IllegalArgumentException unsupported(String reason) { return new IllegalArgumentException("Automatic medium classification: "+reason); }
}
