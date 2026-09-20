package dev.rt_render_experiment.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;







final class ConvexVolume implements VolumeBoundary {
    record Point(double x,double y,double z) {
        Point { if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw unsupported("Non-finite volume point");if(x==0)x=0;if(y==0)y=0;if(z==0)z=0; }
        double axis(int axis) { return axis==0?x:axis==1?y:z; }
    }
    record Triangle(Point a,Point b,Point c) {}
    static final class Budget {
        private final long limit;
        private long used;
        Budget(long limit) { this.limit=limit; }
        void spend(long count) { if(count>limit-used)throw new IllegalStateException("Medium geometric predicate preparation pressure");used+=count; }
        long used() { return used; }
    }
    private record Edge(Point lo,Point hi) {}
    private static final class Use { int count,direction;Point from,to; }
    private static final class Face {
        final Triangle reference;
        final int u,v;
        final HashMap<Edge,Use> edges=new HashMap<>();
        final LinkedHashSet<Point> vertices=new LinkedHashSet<>();
        Face(Triangle t,Budget budget) {
            reference=t;
            int axis=dominant(normal(t));int a=(axis+1)%3,b=(axis+2)%3;
            if(orientation(t.a,t.b,t.c,a,b,budget)<0) { int swap=a;a=b;b=swap; }
            u=a;v=b;
        }
        void add(Triangle t,Budget budget) {
            if(orientation(t.a,t.b,t.c,u,v,budget)<=0)throw unsupported("Degenerate or inconsistent volume face orientation");
            edge(edges,t.a,t.b);edge(edges,t.b,t.c);edge(edges,t.c,t.a);
            vertices.add(t.a);vertices.add(t.b);vertices.add(t.c);
        }
        List<Use> boundary(Budget budget) {
            var segments=new ArrayList<PlanarEdges.Segment>();
            for(var use:edges.values()) {
                if(use.count==2 && use.direction==0)continue;
                if(use.count!=1)throw unsupported("Duplicate or inconsistent volume face coverage");
                segments.add(new PlanarEdges.Segment(use.from,use.to));
            }
            var next=new HashMap<Point,Use>();var incoming=new HashSet<Point>();
            for(var split:PlanarEdges.split(segments,vertices,u,v,budget)) {
                if(split.count()==2 && split.direction()==0)continue;
                if(split.count()!=1)throw unsupported("Duplicate or inconsistent subdivided volume face coverage");
                var segment=split.oriented();var use=new Use();use.from=segment.from();use.to=segment.to();
                if(next.put(use.from,use)!=null || !incoming.add(use.to))throw unsupported("Branched volume face boundary");
            }
            if(next.size()<3 || !next.keySet().equals(incoming))throw unsupported("Open volume face boundary");
            var start=next.keySet().stream().min(ConvexVolume::compare).orElseThrow();var cursor=start;
            var result=new ArrayList<Use>();
            do {
                var use=next.get(cursor);result.add(use);cursor=use.to;
                if(result.size()>next.size())throw unsupported("Non-simple volume face boundary");
            } while(!cursor.equals(start));
            if(result.size()!=next.size())throw unsupported("Multiple loops or a hole in a volume face");


            for(var use:result)for(var p:vertices)
                if(orientation(use.from,use.to,p,u,v,budget)<0)throw unsupported("Non-convex or overlapping volume face coverage");
            return result;
        }
    }
    private record Plane(Point anchor,Point normal,double scale) {}
    private final SceneInputs.Origin origin;
    private final SceneInputs.Transform transform;
    private final double[][] inverse;
    private final double condition;
    private final List<Point> worldVertices;
    private final List<Plane> planes;

    private ConvexVolume(SceneInputs.Geometry reference,List<Triangle> triangles,Budget budget) {
        origin=reference.origin();transform=reference.current();inverse=inverse(transform);
        condition=condition(transform,inverse);
        if(!Double.isFinite(condition) || condition>1e6)throw unsupported("Ill-conditioned volume reference");
        var vertices=new LinkedHashSet<Point>();var faces=new ArrayList<Face>();
        for(var t:triangles) {

            budget.spend(1);normal(t);
            vertices.add(t.a);vertices.add(t.b);vertices.add(t.c);
            Face selected=null;
            for(var face:faces)if(side(face.reference,t.a,budget)==0 && side(face.reference,t.b,budget)==0 && side(face.reference,t.c,budget)==0) { selected=face;break; }
            if(selected==null) { selected=new Face(t,budget);faces.add(selected); }
            selected.add(t,budget);
        }
        if(faces.size()<4)throw unsupported("Volume has no closed three-dimensional extent");
        var boundaries=new ArrayList<Use>();var compiled=new ArrayList<Plane>();
        for(var face:faces) {
            boolean interior=false;
            for(var p:vertices) {
                int sign=side(face.reference,p,budget);
                if(sign>0)throw unsupported("Concave or inward volume boundary");
                interior|=sign<0;
            }
            if(!interior)throw unsupported("Volume has no three-dimensional interior");
            boundaries.addAll(face.boundary(budget));
            var n=normal(face.reference);
            double x=inverse[0][0]*n.x+inverse[1][0]*n.y+inverse[2][0]*n.z;
            double y=inverse[0][1]*n.x+inverse[1][1]*n.y+inverse[2][1]*n.z;
            double z=inverse[0][2]*n.x+inverse[1][2]*n.y+inverse[2][2]*n.z;
            double scale=Math.hypot(Math.hypot(x,y),z);
            if(!(scale>0) || !Double.isFinite(scale))throw unsupported("Unrepresentable volume plane");
            compiled.add(new Plane(face.reference.a,n,scale));
        }
        closeBoundary(boundaries,budget);
        planes=List.copyOf(compiled);worldVertices=vertices.stream().map(this::world).toList();
    }
    static ConvexVolume compile(SceneInputs.Geometry reference,List<Triangle> triangles,Budget budget) { return new ConvexVolume(reference,triangles,budget); }

    private static void closeBoundary(List<Use> boundary,Budget budget) {
        var vertices=new LinkedHashSet<Point>();for(var use:boundary) { vertices.add(use.from);vertices.add(use.to); }
        var edges=new HashMap<Edge,Use>();
        for(var use:boundary) {
            int axis=dominant(new Point(use.to.x-use.from.x,use.to.y-use.from.y,use.to.z-use.from.z));
            double lo=Math.min(use.from.axis(axis),use.to.axis(axis)),hi=Math.max(use.from.axis(axis),use.to.axis(axis));
            var split=new ArrayList<Point>();
            for(var p:vertices) {
                budget.spend(1);
                if(p.axis(axis)<lo || p.axis(axis)>hi)continue;
                if(orientation(use.from,use.to,p,0,1,budget)==0 && orientation(use.from,use.to,p,1,2,budget)==0 && orientation(use.from,use.to,p,2,0,budget)==0)split.add(p);
            }
            split.sort(Comparator.comparingDouble(p->p.axis(axis)));
            boolean forward=use.from.axis(axis)<use.to.axis(axis);
            for(int i=1;i<split.size();i++)edge(edges,split.get(forward?i-1:i),split.get(forward?i:i-1));
        }


        for(var use:edges.values())if(use.count!=2 || use.direction!=0)throw unsupported("Open or multiply covered volume boundary");
    }
    static Point point(SceneInputs.Geometry reference,SceneInputs.Geometry source,double x,double y,double z) {
        for(int r=0;r<3;r++)for(int c=0;c<3;c++)if(reference.current().get(r,c)!=source.current().get(r,c))
            throw unsupported("Contributing volume meshes use different linear transforms");
        if(reference.origin().equals(source.origin()) && reference.current().get(0,3)==source.current().get(0,3)
            && reference.current().get(1,3)==source.current().get(1,3) && reference.current().get(2,3)==source.current().get(2,3))return new Point(x,y,z);
        var inverse=inverse(reference.current());
        double[] delta={source.origin().x()-reference.origin().x()+source.current().get(0,3)-reference.current().get(0,3),
            source.origin().y()-reference.origin().y()+source.current().get(1,3)-reference.current().get(1,3),
            source.origin().z()-reference.origin().z()+source.current().get(2,3)-reference.current().get(2,3)};
        return new Point(x+dot(inverse[0],delta),y+dot(inverse[1],delta),z+dot(inverse[2],delta));
    }
    private static void edge(HashMap<Edge,Use> edges,Point a,Point b) {
        int direction=compare(a,b);if(direction==0)throw unsupported("Zero-length volume edge");
        var key=direction<0?new Edge(a,b):new Edge(b,a);var use=edges.computeIfAbsent(key,k->new Use());
        use.count++;use.direction+=Integer.signum(direction);use.from=a;use.to=b;
    }
    static int compare(Point a,Point b) { int x=Double.compare(a.x,b.x);if(x!=0)return x;int y=Double.compare(a.y,b.y);return y!=0?y:Double.compare(a.z,b.z); }
    static int dominant(Point n) { return Math.abs(n.x)>=Math.abs(n.y) && Math.abs(n.x)>=Math.abs(n.z)?0:Math.abs(n.y)>=Math.abs(n.z)?1:2; }
    private static BigDecimal exact(double x) { return new BigDecimal(x); }
    private static BigDecimal component(Triangle t,int u,int v) {
        return exact(t.b.axis(u)).subtract(exact(t.a.axis(u))).multiply(exact(t.c.axis(v)).subtract(exact(t.a.axis(v))))
            .subtract(exact(t.b.axis(v)).subtract(exact(t.a.axis(v))).multiply(exact(t.c.axis(u)).subtract(exact(t.a.axis(u)))));
    }
    static Point normal(Triangle t) {


        var x=component(t,1,2);var y=component(t,2,0);var z=component(t,0,1);
        var maximum=x.abs().max(y.abs()).max(z.abs());if(maximum.signum()==0)throw unsupported("Degenerate volume triangle");
        var context=java.math.MathContext.DECIMAL128;
        return new Point(x.divide(maximum,context).doubleValue(),y.divide(maximum,context).doubleValue(),z.divide(maximum,context).doubleValue());
    }
    static int orientation(Point a,Point b,Point c,int u,int v,Budget budget) {
        budget.spend(1);
        double x=(b.axis(u)-a.axis(u))*(c.axis(v)-a.axis(v)),y=(b.axis(v)-a.axis(v))*(c.axis(u)-a.axis(u));
        double determinant=x-y,error=(Math.abs(x)+Math.abs(y))*16*Math.ulp(1.0);
        if(Double.isFinite(determinant) && Math.abs(determinant)>Math.max(error,Double.MIN_NORMAL))return determinant>0?1:-1;
        return component(new Triangle(a,b,c),u,v).signum();
    }
    static int side(Triangle t,Point p,Budget budget) {
        budget.spend(1);
        double ax=t.b.x-t.a.x,ay=t.b.y-t.a.y,az=t.b.z-t.a.z,bx=t.c.x-t.a.x,by=t.c.y-t.a.y,bz=t.c.z-t.a.z;
        double cx=p.x-t.a.x,cy=p.y-t.a.y,cz=p.z-t.a.z;
        double determinant=(ay*bz-az*by)*cx+(az*bx-ax*bz)*cy+(ax*by-ay*bx)*cz;
        double permanent=(Math.abs(ay*bz)+Math.abs(az*by))*Math.abs(cx)+(Math.abs(az*bx)+Math.abs(ax*bz))*Math.abs(cy)+(Math.abs(ax*by)+Math.abs(ay*bx))*Math.abs(cz);
        if(Double.isFinite(determinant) && Math.abs(determinant)>Math.max(permanent*32*Math.ulp(1.0),Double.MIN_NORMAL))return determinant>0?1:-1;
        return component(t,1,2).multiply(exact(p.x).subtract(exact(t.a.x)))
            .add(component(t,2,0).multiply(exact(p.y).subtract(exact(t.a.y))))
            .add(component(t,0,1).multiply(exact(p.z).subtract(exact(t.a.z)))).signum();
    }
    private Point local(SceneInputs.Origin p) {
        double[] delta={p.x()-origin.x()-transform.get(0,3),p.y()-origin.y()-transform.get(1,3),p.z()-origin.z()-transform.get(2,3)};
        return new Point(dot(inverse[0],delta),dot(inverse[1],delta),dot(inverse[2],delta));
    }
    private Point world(Point p) {
        double[] local={p.x,p.y,p.z},o={origin.x(),origin.y(),origin.z()},result=new double[3];
        for(int r=0;r<3;r++) { result[r]=o[r]+transform.get(r,3);for(int c=0;c<3;c++)result[r]+=(double)transform.get(r,c)*local[c]; }
        return new Point(result[0],result[1],result[2]);
    }

    @Override public double clearance(SceneInputs.Origin eye) {
        var p=local(eye);double inside=Double.POSITIVE_INFINITY,outside=0;
        for(var plane:planes) {
            var n=plane.normal;var a=plane.anchor;
            double distance=-(n.x*(p.x-a.x)+n.y*(p.y-a.y)+n.z*(p.z-a.z))/plane.scale;
            if(!Double.isFinite(distance))throw unsupported("Unrepresentable volume clearance");
            inside=Math.min(inside,distance);outside=Math.max(outside,-distance);
        }
        return outside>0?-outside:inside;
    }
    @Override public boolean strictlyContains(VolumeBoundary other,Budget budget) {
        for(var p:other.vertices()) {
            budget.spend(planes.size());
            if(clearance(new SceneInputs.Origin(p.x,p.y,p.z))<=uncertainty(p))return false;
        }
        return true;
    }
    @Override public List<Point> vertices() { return worldVertices; }
    @Override public double condition() { return condition; }
    @Override public double uncertainty(SceneInputs.Origin eye,SceneInputs.Origin frameOrigin) {
        double magnitude=1;
        for(var p:worldVertices)magnitude=Math.max(magnitude,Math.max(Math.abs(p.x-frameOrigin.x()),Math.max(Math.abs(p.y-frameOrigin.y()),Math.abs(p.z-frameOrigin.z()))));
        return 1e-5+8*Math.ulp((float)magnitude)+uncertainty(new Point(eye.x(),eye.y(),eye.z()));
    }
    private double uncertainty(Point p) {
        double magnitude=Math.max(1,Math.max(Math.abs(p.x),Math.max(Math.abs(p.y),Math.abs(p.z))));
        return 32*condition*Math.ulp(magnitude);
    }


    @Override public long logicalBytes() { return 160L+56L*planes.size()+24L*worldVertices.size(); }
    static double[][] inverse(SceneInputs.Transform m) {
        double a=m.get(0,0),b=m.get(0,1),c=m.get(0,2),d=m.get(1,0),e=m.get(1,1),f=m.get(1,2),g=m.get(2,0),h=m.get(2,1),i=m.get(2,2);
        double determinant=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
        if(determinant==0 || !Double.isFinite(determinant))throw unsupported("Singular volume reference");
        return new double[][]{{(e*i-f*h)/determinant,(c*h-b*i)/determinant,(b*f-c*e)/determinant},
            {(f*g-d*i)/determinant,(a*i-c*g)/determinant,(c*d-a*f)/determinant},
            {(d*h-e*g)/determinant,(b*g-a*h)/determinant,(a*e-b*d)/determinant}};
    }
    static double condition(SceneInputs.Transform m,double[][] inverse) {
        double norm=0,inverseNorm=0;
        for(int r=0;r<3;r++) { double a=0,b=0;for(int c=0;c<3;c++) { a+=Math.abs(m.get(r,c));b+=Math.abs(inverse[r][c]); }norm=Math.max(norm,a);inverseNorm=Math.max(inverseNorm,b); }
        return Math.max(1,norm*inverseNorm);
    }
    private static double dot(double[] a,double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    private static IllegalArgumentException unsupported(String reason) { return new IllegalArgumentException("Automatic medium classification: "+reason); }
}
