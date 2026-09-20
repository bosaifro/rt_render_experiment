package dev.rt_render_experiment.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.engine.ConvexVolume.Point;
import dev.rt_render_experiment.engine.ConvexVolume.Triangle;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VolumeRelationsTest {
    private static final List<Triangle> CUBE=TriangleVolumeTest.boundary(Set.of(new TriangleVolumeTest.Cell(0,0,0)));
    static List<Triangle> elbow() { return TriangleVolumeTest.boundary(Set.of(new TriangleVolumeTest.Cell(0,0,0),new TriangleVolumeTest.Cell(1,0,0),new TriangleVolumeTest.Cell(0,0,1))); }
    static MediumInputs.Domain domain(int count) {
        var values=new ArrayList<MediumInputs.Volume>();for(int id=1;id<=count;id++)values.add(new MediumInputs.Volume(identity(id),MediumProfiles.definition(MediumInputs.Kind.GLASS,new SceneInputs.Vec3(.8f,.8f,.8f))));
        return new MediumInputs.Domain(values);
    }
    static long identity(int id) { return ((long)id<<32)|1; }
    @Test void concaveParentsSupportNestedConvexAndConcaveChildrenWithStableDepth() {
        var parent=mesh(elbow(),1,placement(1,0,0,0));
        for(var source:List.of(CUBE,elbow())) {
            var child=mesh(source,2,placement(.25f,.25f,.25f,.25f));
            var compiler=new SceneMediaCompiler();var scene=scene(parent,child);var prepared=compiler.prepare(scene,domain(2));
            var origin=prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.375,64.375,.375))).initialMedia().orElseThrow();
            assertEquals(List.of(identity(1),identity(2)),origin.enclosures().stream().map(MediumInputs.Enclosure::volume).toList());
            assertEquals(2,compiler.prepare(scene,domain(2)).work().reusedVolumes());
            var permuted=new SceneMediaCompiler().prepare(scene(child,parent),domain(2)).classify(SceneMediaCompilerTest.at(origin.position()));
            assertEquals(origin.enclosures(),permuted.initialMedia().orElseThrow().enclosures());
        }
    }
    @Test void airHoleAndInterleavedBoundsDoNotImplyContainment() {
        var cells=new java.util.HashSet<TriangleVolumeTest.Cell>();for(int x=0;x<3;x++)for(int z=0;z<3;z++)if(x!=1 || z!=1)cells.add(new TriangleVolumeTest.Cell(x,0,z));
        var ring=mesh(TriangleVolumeTest.boundary(cells),1,placement(1,0,0,0));var child=mesh(CUBE,2,placement(.5f,1.25f,.25f,1.25f));
        var prepared=new SceneMediaCompiler().prepare(scene(ring,child),domain(2));
        assertEquals(List.of(identity(2)),prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1.5,64.5,1.5))).initialMedia().orElseThrow().enclosures().stream().map(MediumInputs.Enclosure::volume).toList());
        assertEquals(List.of(identity(1)),prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(.5,64.5,.5))).initialMedia().orElseThrow().enclosures().stream().map(MediumInputs.Enclosure::volume).toList());
        assertTrue(prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1.125,64.5,1.5))).initialMedia().orElseThrow().enclosures().isEmpty());
    }
    @Test void everyChildVertexInsideIsInsufficientWhenEdgesBridgeAConcavity() {
        var shape=TriangleVolumeTest.boundary(Set.of(new TriangleVolumeTest.Cell(0,0,0),new TriangleVolumeTest.Cell(1,0,0),new TriangleVolumeTest.Cell(2,0,0),new TriangleVolumeTest.Cell(0,0,1),new TriangleVolumeTest.Cell(0,0,2)));
        var parent=mesh(shape,1,placement(1,0,0,0));
        var child=mesh(CUBE,2,new SceneInputs.Transform(new float[]{2,0,.25f,.375f, 0,.5f,0,.25f, -2,0,.25f,2.375f}));
        var a=TriangleVolume.compile(parent.input(),shape,new ConvexVolume.Budget(4_000_000));
        var b=ConvexVolume.compile(child.input(),CUBE,new ConvexVolume.Budget(4_000_000));
        for(var p:b.vertices())assertTrue(a.clearance(new SceneInputs.Origin(p.x(),p.y(),p.z()))>.1);
        assertThrows(IllegalArgumentException.class,()->new SceneMediaCompiler().prepare(scene(parent,child),domain(2)));
    }
    @Test void touchingCoincidentAndNumericallyUnseparatedBoundariesRefuse() {
        var parent=mesh(elbow(),1,placement(1,0,0,0));
        for(var pose:List.of(placement(.5f,.25f,.5f,.25f),placement(.5f,1,.25f,1),placement(.5f,1.0000001f,.25f,1.0000001f)))
            assertThrows(IllegalArgumentException.class,()->new SceneMediaCompiler().prepare(scene(parent,mesh(CUBE,2,pose)),domain(2)));
        assertThrows(IllegalArgumentException.class,()->new SceneMediaCompiler().prepare(scene(parent,mesh(elbow(),2,placement(1,0,0,0))),domain(2)));
        var separated=new SceneMediaCompiler().prepare(scene(parent,mesh(CUBE,2,placement(.5f,1.001f,.25f,1.001f))),domain(2));
        assertEquals(1,separated.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1.25,64.5,1.25))).initialMedia().orElseThrow().enclosures().size());
    }
    @Test void mixedAffinePlacementsAndLargeOriginsPreserveContainment() {
        var origin=new SceneInputs.Origin(-30_000_000,96,30_000_000);
        var parent=mesh(elbow(),1,new SceneInputs.Transform(new float[]{0,0,-2,0, 0,.5f,0,0, -1,0,0,0}),origin);
        var child=mesh(CUBE,2,placement(.1f,0,0,0),new SceneInputs.Origin(origin.x()-1.05,origin.y()+.2,origin.z()-.55));
        var prepared=new SceneMediaCompiler().prepare(scene(parent,child),domain(2));
        assertEquals(2,prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(origin.x()-1,origin.y()+.25,origin.z()-.5))).initialMedia().orElseThrow().enclosures().size());
    }
    @Test void failedPairPublicationAndPairPressureRetainThePredecessor() {
        var parent=mesh(elbow(),1,placement(1,0,0,0));var child=mesh(CUBE,2,placement(.25f,.25f,.25f,.25f));
        var good=scene(parent,child);var complete=new SceneMediaCompiler().prepare(good,domain(2));
        var compiler=new SceneMediaCompiler();compiler.prepare(good,domain(2));
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(scene(parent,mesh(CUBE,2,placement(.5f,1,.25f,1))),domain(2)));
        assertEquals(2,compiler.prepare(good,domain(2)).work().reusedVolumes());
        var limited=new SceneMediaCompiler(new SceneMediaCompiler.Limits(8,1000,complete.work().predicates()-1));
        assertThrows(IllegalStateException.class,()->limited.prepare(good,domain(2)));
        var capped=new SceneMediaCompiler(new SceneMediaCompiler.Limits(8,1000,complete.work().predicates()));capped.prepare(good,domain(2));
        assertThrows(IllegalStateException.class,()->capped.prepare(scene(parent,mesh(CUBE,2,placement(.5f,1,.25f,1))),domain(2)));
        assertEquals(2,capped.prepare(good,domain(2)).work().reusedVolumes());
    }
    @Test void outwardArithmeticEnclosesIndependentExactExpressions() {
        var random=new java.util.Random(824319);
        for(int i=0;i<2000;i++) {
            double a=Math.scalb(random.nextDouble()-.5,random.nextInt(801)-400),b=Math.scalb(random.nextDouble()-.5,random.nextInt(801)-400);
            double c=Math.scalb(random.nextDouble()-.5,random.nextInt(401)-200),d=i%2==0?30_000_000:random.nextDouble();
            var interval=BoundaryRelations.Interval.subtract(a,b).multiply(c).add(BoundaryRelations.Interval.point(d));
            var exact=new BigDecimal(a).subtract(new BigDecimal(b)).multiply(new BigDecimal(c)).add(new BigDecimal(d));
            assertTrue(new BigDecimal(interval.lo()).compareTo(exact)<=0);assertTrue(new BigDecimal(interval.hi()).compareTo(exact)>=0);
        }
    }
    @Test void triangleSeparationIncludesContactsAndCoplanarEdges() {
        var a=new Triangle(new Point(0,0,0),new Point(2,0,0),new Point(0,2,0));
        for(var b:List.of(a,new Triangle(a.a(),a.b(),new Point(0,-2,0)),new Triangle(a.a(),a.b(),new Point(0,0,2)),
            new Triangle(new Point(.25,.25,0),new Point(.5,.25,0),new Point(.25,.5,0))))
            assertFalse(BoundaryRelations.separated(a,b,0,new ConvexVolume.Budget(1000)));
        var b=new Triangle(new Point(3,0,0),new Point(4,0,0),new Point(3,1,0));
        assertTrue(BoundaryRelations.separated(a,b,.5,new ConvexVolume.Budget(1000)));
    }
    @Test void relationViewsAreScratchAndHaveBoundedScalarStorage() {
        var parent=mesh(elbow(),1,placement(1,0,0,0));var child=mesh(CUBE,2,placement(.5f,.25f,.25f,.25f));
        var budget=new ConvexVolume.Budget(4_000_000);var anchor=parent.input().origin();
        var a=BoundaryRelations.prepare(parent.input(),elbow(),anchor,budget);var b=BoundaryRelations.prepare(child.input(),CUBE,anchor,budget);
        assertEquals(6576,a.logicalBytes()+b.logicalBytes());
        assertTrue(a.error()>=0 && b.error()>=0);assertTrue(budget.used()>0);
    }
    @Test void separationAgreesWithAnIndependentIntegerProjectionOracle() {
        var random=new java.util.Random(678143);int checked=0;
        while(checked<1000) {
            var a=integerTriangle(random);var b=integerTriangle(random);var an=cross(sub(a[1],a[0]),sub(a[2],a[0]));var bn=cross(sub(b[1],b[0]),sub(b[2],b[0]));
            if(dot(an,an)==0 || dot(bn,bn)==0)continue;
            var axes=new ArrayList<long[]>();axes.add(an);axes.add(bn);
            for(int i=0;i<3;i++) {
                var ae=sub(a[(i+1)%3],a[i]);axes.add(cross(an,ae));axes.add(cross(bn,sub(b[(i+1)%3],b[i])));
                for(int j=0;j<3;j++)axes.add(cross(ae,sub(b[(j+1)%3],b[j])));
            }
            boolean separated=false;
            for(var axis:axes) {
                long alo=Long.MAX_VALUE,ahi=Long.MIN_VALUE,blo=Long.MAX_VALUE,bhi=Long.MIN_VALUE;
                for(int i=0;i<3;i++) { long av=dot(axis,a[i]),bv=dot(axis,b[i]);alo=Math.min(alo,av);ahi=Math.max(ahi,av);blo=Math.min(blo,bv);bhi=Math.max(bhi,bv); }
                separated |= ahi<blo || bhi<alo;
            }
            assertEquals(separated,BoundaryRelations.separated(triangle(a),triangle(b),0,new ConvexVolume.Budget(1000)));checked++;
        }
    }
    private static long[][] integerTriangle(java.util.Random random) { var p=new long[3][3];for(var vertex:p)for(int i=0;i<3;i++)vertex[i]=random.nextInt(33)-16;return p; }
    private static Triangle triangle(long[][] p) { return new Triangle(new Point(p[0][0],p[0][1],p[0][2]),new Point(p[1][0],p[1][1],p[1][2]),new Point(p[2][0],p[2][1],p[2][2])); }
    private static long[] sub(long[] a,long[] b) { return new long[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]}; }
    private static long[] cross(long[] a,long[] b) { return new long[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]}; }
    private static long dot(long[] a,long[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static SceneStore.Revision scene(SceneCompiler.Compiled... meshes) { return new SceneStore.Revision(1,1,List.of(meshes),java.util.Arrays.stream(meshes).mapToLong(SceneCompiler.Compiled::bytes).sum()); }
    static SceneInputs.Transform placement(float scale,float x,float y,float z) { return new SceneInputs.Transform(new float[]{scale,0,0,x, 0,scale,0,y, 0,0,scale,z}); }
    static SceneCompiler.Compiled mesh(List<Triangle> triangles,int id,SceneInputs.Transform placement) { return mesh(triangles,id,placement,new SceneInputs.Origin(0,64,0)); }
    static SceneCompiler.Compiled mesh(List<Triangle> triangles,int id,SceneInputs.Transform placement,SceneInputs.Origin origin) {
        var base=TriangleVolumeTest.scene(triangles).geometry().getFirst().input();var primitives=new ArrayList<SceneInputs.Primitive>();
        for(var p:base.primitives()) {
            var s=p.surface().withNestedVolume(identity(id));primitives.add(new SceneInputs.Primitive(p.part(),p.ordinal(),s,p.corners()));
        }
        return new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(9901,id),base.revision(),origin,placement,placement,false,base.motion(),base.participation(),primitives));
    }
}
