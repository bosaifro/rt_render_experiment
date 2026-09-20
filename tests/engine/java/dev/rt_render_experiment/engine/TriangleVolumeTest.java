package dev.rt_render_experiment.engine;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.engine.ConvexVolume.Point;
import dev.rt_render_experiment.engine.ConvexVolume.Triangle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TriangleVolumeTest {
    record Cell(int x,int y,int z) {}
    private static final MediumInputs.Domain DOMAIN=new MediumInputs.Domain(SceneMediaCompilerTest.domain().volumes().subList(0,1));
    @Test void closedConcaveFluidRegionUsesItsActualBoundary() {
        var cells=new HashSet<FluidVolumeCompilerTest.Cell>();
        for(int x=0;x<3;x++)for(int y=0;y<3;y++)for(int z=0;z<3;z++)cells.add(new FluidVolumeCompilerTest.Cell(x,y,z));
        var source=FluidVolumeCompilerTest.source(cells,true);
        var result=new ModelVolumeCompiler().prepare(FluidVolumeCompilerTest.frame(1,source));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().detail());
        var compiled=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow());
        assertEquals(1,compiled.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1.5,65.5,1.5))).initialMedia().orElseThrow().enclosures().size());
        assertTrue(compiled.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(3.5,65.5,1.5))).initialMedia().orElseThrow().enclosures().isEmpty());
        for(int i=0;i<source.geometry().size();i++) {
            var a=source.geometry().get(i);var b=result.scene().revision().geometry().get(i);
            assertSame(a.input(),b.input());assertEquals(-1,a.positions().mismatch(b.positions()));assertEquals(-1,a.corners().mismatch(b.corners()));assertEquals(-1,a.indices().mismatch(b.indices()));
        }
    }
    @Test void concaveFootprintsAndHolesAgreeWithIndependentCellAndRectangleQueries() {
        var ring=new HashSet<Cell>();for(int x=0;x<3;x++)for(int z=0;z<3;z++)if(x!=1 || z!=1)ring.add(new Cell(x,0,z));
        for(var cells:List.of(Set.of(new Cell(0,0,0),new Cell(1,0,0),new Cell(0,0,1)),ring)) {
            var triangles=boundary(cells);var scene=scene(triangles);var certificate=TriangleVolume.compile(scene.geometry().getFirst().input(),triangles,new ConvexVolume.Budget(4_000_000));
            for(int x=-2;x<=14;x++)for(int y=-2;y<=6;y++)for(int z=-2;z<=14;z++) {
                var p=new Point(x*.25,y*.25,z*.25);double expected=distance(cells,p);
                if(expected==0) { assertEquals(0,certificate.clearance(new SceneInputs.Origin(p.x(),64+p.y(),p.z())),1e-9);continue; }
                boolean inside=cells.stream().anyMatch(c->p.x()>c.x && p.x()<c.x+1 && p.y()>c.y && p.y()<c.y+1 && p.z()>c.z && p.z()<c.z+1);

                if(!inside)inside=cells.stream().anyMatch(c->p.x()>=c.x && p.x()<=c.x+1 && p.y()>=c.y && p.y()<=c.y+1 && p.z()>=c.z && p.z()<=c.z+1);
                assertEquals(inside?expected:-expected,certificate.clearance(new SceneInputs.Origin(p.x(),64+p.y(),p.z())),2e-9,"point="+p);
            }
            var prepared=new SceneMediaCompiler().prepare(scene,DOMAIN);
            assertEquals(1,prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.5,.5))).initialMedia().orElseThrow().enclosures().size());
            assertTrue(prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1.5,64.5,1.5))).initialMedia().orElseThrow().enclosures().isEmpty());
        }
    }
    @Test void triangleIntersectionsAndBoundaryContactsStayDistinctUnderExactAffineChanges() {
        var a=triangle(0,0,0, 2,0,0, 0,2,0);
        var cases=List.of(
            new Pair(triangle(3,0,0, 4,0,0, 3,1,0),false),new Pair(triangle(0,0,0, 2,0,0, 0,-2,0),false),
            new Pair(triangle(0,0,0, 2,0,0, 0,0,2),false),new Pair(triangle(1,0,0, 2,0,0, 1,0,1),false),
            new Pair(triangle(0,0,0, -1,0,1, 0,-1,1),false),new Pair(triangle(.25,.25,0, .5,.25,0, .25,.5,0),true),
            new Pair(a,true),new Pair(triangle(0,2,0, 2,0,0, 0,0,0),true),
            new Pair(triangle(.5,.5,-1, .5,.5,1, 2,2,0),true),new Pair(triangle(.25,.25,0, 0,0,1, 1,0,1),true),
            new Pair(triangle(-1,1,0, 1,1,0, 0,1,1),true),new Pair(triangle(0,0,Math.scalb(1.0,-100),2,0,Math.scalb(1.0,-100),0,2,Math.scalb(1.0,-100)),false));
        for(int variation=0;variation<24;variation++) {
            final int variant=variation;
            for(var pair:cases) {
                var first=map(a,p->transform(p,variant));var second=map(pair.triangle,p->transform(p,variant));
                assertEquals(pair.intersects,TriangleVolume.crosses(first,second,new ConvexVolume.Budget(1000)),"variant="+variant+" pair="+pair);
                assertEquals(pair.intersects,TriangleVolume.crosses(second,first,new ConvexVolume.Budget(1000)));
            }
        }
    }
    private record Pair(Triangle triangle,boolean intersects) {}
    @Test void transverseIntersectionPredicatesAgreeWithAnIndependentIntegerSeparatingAxisOracle() {
        var random=new java.util.Random(892431);int checked=0;
        while(checked<2000) {
            long[][] a=integerTriangle(random),b=integerTriangle(random);long[] an=cross(sub(a[1],a[0]),sub(a[2],a[0])),bn=cross(sub(b[1],b[0]),sub(b[2],b[0]));
            if(dot(an,an)==0 || dot(bn,bn)==0)continue;
            boolean generic=true;for(int i=0;i<3;i++)generic &= dot(an,sub(b[i],a[0]))!=0 && dot(bn,sub(a[i],b[0]))!=0;
            if(!generic)continue;
            var axes=new ArrayList<long[]>();axes.add(an);axes.add(bn);
            for(int i=0;i<3;i++)for(int j=0;j<3;j++)axes.add(cross(sub(a[(i+1)%3],a[i]),sub(b[(j+1)%3],b[j])));
            boolean separated=false;
            for(var axis:axes) {
                long alo=Long.MAX_VALUE,ahi=Long.MIN_VALUE,blo=Long.MAX_VALUE,bhi=Long.MIN_VALUE;
                for(int i=0;i<3;i++) { long av=dot(axis,a[i]),bv=dot(axis,b[i]);alo=Math.min(alo,av);ahi=Math.max(ahi,av);blo=Math.min(blo,bv);bhi=Math.max(bhi,bv); }
                separated |= ahi<blo || bhi<alo;
            }
            assertEquals(!separated,TriangleVolume.crosses(integerTriangle(a),integerTriangle(b),new ConvexVolume.Budget(1000)));checked++;
        }
    }
    private static long[][] integerTriangle(java.util.Random random) { var p=new long[3][3];for(var vertex:p)for(int i=0;i<3;i++)vertex[i]=random.nextInt(33)-16;return p; }
    private static Triangle integerTriangle(long[][] p) { return triangle(p[0][0],p[0][1],p[0][2],p[1][0],p[1][1],p[1][2],p[2][0],p[2][1],p[2][2]); }
    private static long[] sub(long[] a,long[] b) { return new long[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
    private static long[] cross(long[] a,long[] b) { return new long[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
    private static long dot(long[] a,long[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    @Test void malformedOrSelfIntersectingClosedTopologyRejectsAndPressureRetainsThePredecessor() {
        var base=boundary(Set.of(new Cell(0,0,0)));var variants=new ArrayList<List<Triangle>>();
        var open=new ArrayList<>(base);open.removeLast();variants.add(open);
        var duplicate=new ArrayList<>(base);duplicate.add(base.getFirst());variants.add(duplicate);
        variants.add(base.stream().map(t->new Triangle(t.c(),t.b(),t.a())).toList());
        variants.add(base.stream().map(t->map(t,p->p.equals(new Point(0,0,0))?new Point(1.25,1.25,1.25):p)).toList());
        var disconnected=new ArrayList<>(base);disconnected.addAll(base.stream().map(t->map(t,p->new Point(p.x()+2,p.y(),p.z()))).toList());variants.add(disconnected);
        var touching=new ArrayList<>(base);touching.addAll(base.stream().map(t->map(t,p->new Point(p.x()+1,p.y()+1,p.z()+1))).toList());variants.add(touching);
        for(var triangles:variants)assertThrows(IllegalArgumentException.class,()->TriangleVolume.compile(scene(base).geometry().getFirst().input(),triangles,new ConvexVolume.Budget(4_000_000)));
        var convex=scene(base);var old=new SceneMediaCompiler().prepare(convex,DOMAIN);
        var compiler=new SceneMediaCompiler(new SceneMediaCompiler.Limits(8,1000,old.work().predicates()));compiler.prepare(convex,DOMAIN);
        assertThrows(IllegalStateException.class,()->compiler.prepare(scene(boundary(Set.of(new Cell(0,0,0),new Cell(1,0,0),new Cell(0,0,1)))),DOMAIN));
        assertEquals(1,compiler.prepare(convex,DOMAIN).work().reusedVolumes());
    }
    @Test void negativePlacementAndLargeOriginsPreserveConcaveContainment() {
        var triangles=boundary(Set.of(new Cell(0,0,0),new Cell(1,0,0),new Cell(0,0,1)));var original=scene(triangles).geometry().getFirst().input();
        var pose=new SceneInputs.Transform(new float[]{0,0,-2,0, 0,.5f,0,0, -1,0,0,0});
        var origin=new SceneInputs.Origin(-30_000_000,96,30_000_000);
        var input=new SceneInputs.Geometry(original.key(),original.revision(),origin,pose,pose,false,original.motion(),original.participation(),original.primitives());
        var certificate=TriangleVolume.compile(input,triangles,new ConvexVolume.Budget(4_000_000));
        assertEquals(.25,certificate.clearance(new SceneInputs.Origin(origin.x()-1,origin.y()+.25,origin.z()-.5)),1e-8);
        assertTrue(certificate.clearance(new SceneInputs.Origin(origin.x()-3,origin.y()+.25,origin.z()-1.5))<0);
    }
    @Test void convexAndConcaveParentsUseCertifiedContainment() {
        var cube=boundary(Set.of(new Cell(0,0,0)));var elbow=boundary(Set.of(new Cell(0,0,0),new Cell(1,0,0),new Cell(0,0,1)));
        var outer=scene(cube.stream().map(t->map(t,p->new Point(p.x()*4,p.y()*4,p.z()*4))).toList()).geometry().getFirst();
        var child=identified(scene(elbow.stream().map(t->map(t,p->new Point(p.x()+1,p.y()+1,p.z()+1))).toList()).geometry().getFirst(),2);
        var prepared=new SceneMediaCompiler().prepare(new SceneStore.Revision(1,1,List.of(outer,child),outer.bytes()+child.bytes()),SceneMediaCompilerTest.domain());
        assertEquals(2,prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1.5,65.5,1.5))).initialMedia().orElseThrow().enclosures().size());
        var concave=scene(elbow).geometry().getFirst();
        var small=identified(scene(cube.stream().map(t->map(t,p->new Point(p.x()*.25+.25,p.y()*.25+.25,p.z()*.25+.25))).toList()).geometry().getFirst(),2);
        var nested=new SceneMediaCompiler().prepare(new SceneStore.Revision(1,1,List.of(concave,small),concave.bytes()+small.bytes()),SceneMediaCompilerTest.domain());
        assertEquals(2,nested.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.375,64.375,.375))).initialMedia().orElseThrow().enclosures().size());
    }
    private static SceneCompiler.Compiled identified(SceneCompiler.Compiled source,int id) {
        var g=source.input();var surface=OrientedMediumFixtures.scene(false,0).geometry().get(id-1).parts().getFirst().surface();
        var primitives=g.primitives().stream().map(p->new SceneInputs.Primitive(p.part(),p.ordinal(),surface,p.corners())).toList();
        return new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(g.key().high(),id),g.revision(),g.origin(),g.current(),g.previous(),g.previousValid(),g.motion(),g.participation(),primitives));
    }
    static List<Triangle> boundary(Set<Cell> cells) {
        var result=new ArrayList<Triangle>();
        for(var cell:cells.stream().sorted(java.util.Comparator.comparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z)).toList())for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
            int[] adjacent={cell.x,cell.y,cell.z};adjacent[axis]+=sign;if(cells.contains(new Cell(adjacent[0],adjacent[1],adjacent[2])))continue;
            int u=(axis+1)%3,v=(axis+2)%3;if(sign<0) { int swap=u;u=v;v=swap; }
            var points=new ArrayList<Point>();
            for(int[] corner:new int[][]{{0,0},{1,0},{1,1},{0,1}}) {
                double[] p={cell.x,cell.y,cell.z};p[axis]+=sign>0?1:0;p[u]+=corner[0];p[v]+=corner[1];points.add(new Point(p[0],p[1],p[2]));
            }
            result.add(new Triangle(points.get(0),points.get(1),points.get(2)));result.add(new Triangle(points.get(0),points.get(2),points.get(3)));
        }
        return List.copyOf(result);
    }
    static SceneStore.Revision scene(List<Triangle> triangles) {
        var source=OrientedMediumFixtures.scene(false,0).geometry().getFirst().input();var template=source.primitives().getFirst();
        var primitives=new ArrayList<SceneInputs.Primitive>();long ordinal=0;
        for(var triangle:triangles) {
            var corners=List.of(triangle.a(),triangle.b(),triangle.c()).stream().map(p->new SceneInputs.Corner(new SceneInputs.Vec3((float)p.x(),(float)p.y(),(float)p.z()),0,0,
                template.corners().getFirst().tint(),new SceneInputs.Vec3(0,0,1),new SceneInputs.Vec3(1,0,0))).toList();
            primitives.add(new SceneInputs.Primitive(template.part(),ordinal++,template.surface(),corners));
        }
        var compiled=new SceneCompiler().compile(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),false,source.motion(),source.participation(),primitives));
        return new SceneStore.Revision(1,1,List.of(compiled),compiled.bytes());
    }
    private static double distance(Set<Cell> cells,Point p) {
        double minimum=Double.POSITIVE_INFINITY;
        for(var cell:cells)for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
            int[] q={cell.x,cell.y,cell.z};q[axis]+=sign;if(cells.contains(new Cell(q[0],q[1],q[2])))continue;
            int[] c={cell.x,cell.y,cell.z};double squared=0;
            for(int i=0;i<3;i++) { double delta=i==axis?p.axis(i)-(c[i]+(sign>0?1:0)):Math.max(Math.max(c[i]-p.axis(i),p.axis(i)-c[i]-1),0);squared+=delta*delta; }
            minimum=Math.min(minimum,squared);
        }
        return Math.sqrt(minimum);
    }
    private static Triangle triangle(double... p) { return new Triangle(new Point(p[0],p[1],p[2]),new Point(p[3],p[4],p[5]),new Point(p[6],p[7],p[8])); }
    private static Triangle map(Triangle t,java.util.function.UnaryOperator<Point> fn) { return new Triangle(fn.apply(t.a()),fn.apply(t.b()),fn.apply(t.c())); }
    private static Point transform(Point p,int variant) {
        double scale=Math.scalb(1.0,variant/6-2);int axis=variant%3;

        return new Point(p.axis(axis)*scale*(variant%2==0?-1:1),p.axis((axis+1)%3)*scale,p.axis((axis+2)%3)*scale);
    }
}
