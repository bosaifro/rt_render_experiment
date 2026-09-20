package dev.rt_render_experiment.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ConvexVolumeTest {
    private static final SceneInputs.Vec3 A=new SceneInputs.Vec3(0,0,0),B=new SceneInputs.Vec3(4,0,0),C=new SceneInputs.Vec3(0,4,0),D=new SceneInputs.Vec3(0,0,4);
    private static final List<List<SceneInputs.Vec3>> TETRA=List.of(List.of(A,C,B),List.of(A,B,D),List.of(A,D,C),List.of(B,C,D));
    private static final MediumInputs.Domain DOMAIN=new MediumInputs.Domain(SceneMediaCompilerTest.domain().volumes().subList(0,1));

    @Test void tetrahedronUsesItsPlanesInsteadOfAnInferredBox() {
        var scene=scene(TETRA);var compiler=new SceneMediaCompiler();var prepared=compiler.prepare(scene,DOMAIN);
        var inside=prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1,65,1))).initialMedia().orElseThrow();
        assertEquals(GlassFixtures.glass().subList(0,1),inside.enclosures());
        assertEquals(1/Math.sqrt(3),inside.clearance(),.00003);

        assertTrue(prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(2,66,2))).initialMedia().orElseThrow().enclosures().isEmpty());
        assertThrows(IllegalArgumentException.class,()->prepared.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1,65,2))));
        assertEquals(4,prepared.work().triangles());assertEquals(528+20,prepared.work().logicalBytes());assertTrue(prepared.work().predicates()>0);
        assertEquals(0,compiler.prepare(scene,DOMAIN).work().predicates());
    }
    @Test void faceEdgeSubdivisionsAndMixedTessellationRetainTheSolid() {
        var faces=new ArrayList<List<SceneInputs.Vec3>>();
        for(int i=0;i<TETRA.size();i++) {
            var t=TETRA.get(i);
            if(i%2==0) { var m=mid(t.get(0),t.get(1));faces.add(List.of(t.get(0),m,t.get(2)));faces.add(List.of(m,t.get(1),t.get(2))); }
            else faces.add(t);
        }
        var result=new SceneMediaCompiler().prepare(scene(faces),DOMAIN);
        assertEquals(6,result.work().triangles());assertEquals(1,result.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1,65,1))).initialMedia().orElseThrow().enclosures().size());
    }
    @Test void negativeAffinePlacementAndLargeCoordinates() {
        var source=scene(TETRA).geometry().getFirst().input();
        var m=new Matrix4f().rotateY(.63f).rotateZ(-.27f).scale(-2,.7f,1.3f);
        var pose=new SceneInputs.Transform(new float[]{m.m00(),m.m10(),m.m20(),0,m.m01(),m.m11(),m.m21(),0,m.m02(),m.m12(),m.m22(),0});
        var origin=new SceneInputs.Origin(-30_000_000,96,30_000_000);
        var compiled=new SceneCompiler().compile(new SceneInputs.Geometry(source.key(),source.revision(),origin,pose,pose,false,source.motion(),source.participation(),source.primitives()));
        var scene=new SceneStore.Revision(1,1,List.of(compiled),compiled.bytes());
        var eye=new SceneInputs.Origin(origin.x()+pose.get(0,0)+pose.get(0,1)+pose.get(0,2),origin.y()+pose.get(1,0)+pose.get(1,1)+pose.get(1,2),origin.z()+pose.get(2,0)+pose.get(2,1)+pose.get(2,2));
        var result=new SceneMediaCompiler().prepare(scene,DOMAIN).classify(SceneMediaCompilerTest.at(eye));
        assertEquals(1,result.initialMedia().orElseThrow().enclosures().size());assertTrue(result.initialMedia().orElseThrow().clearance()>.4);
        InitialMedia.validate(result,1,1,scene.geometry());
    }
    @Test void illConditionedPlacementRefusesAutomaticClassification() {
        var source=scene(TETRA).geometry().getFirst().input();var rows=SceneInputs.Transform.identity().rows();rows[0]=1e-7f;
        var pose=new SceneInputs.Transform(rows);
        var compiled=new SceneCompiler().compile(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),pose,pose,false,source.motion(),source.participation(),source.primitives()));
        var candidate=new SceneStore.Revision(1,1,List.of(compiled),compiled.bytes());
        var failure=assertThrows(IllegalArgumentException.class,()->new SceneMediaCompiler().prepare(candidate,DOMAIN));
        assertTrue(failure.getMessage().contains("Ill-conditioned volume reference"));
    }
    @Test void openDegenerateDuplicateAndCrackedBoundariesReject() {
        var variants=new ArrayList<List<List<SceneInputs.Vec3>>>();
        var open=new ArrayList<>(TETRA);open.removeLast();variants.add(open);
        var reversed=new ArrayList<>(TETRA);reversed.set(3,List.of(B,D,C));variants.add(reversed);
        var duplicate=new ArrayList<>(TETRA);duplicate.add(TETRA.getLast());variants.add(duplicate);
        var degenerate=new ArrayList<>(TETRA);degenerate.set(3,List.of(B,C,mid(B,C)));variants.add(degenerate);
        var crack=new ArrayList<>(TETRA);crack.set(3,List.of(B,C,new SceneInputs.Vec3(0,0,Math.nextDown(4f))));variants.add(crack);
        for(var faces:variants)assertThrows(IllegalArgumentException.class,()->new SceneMediaCompiler().prepare(scene(faces),DOMAIN));
    }
    @Test void predicatePressurePreservesCoherentCacheAndChangedTopologyRecompiles() {
        var source=scene(TETRA);var work=new SceneMediaCompiler().prepare(source,DOMAIN).work();
        var compiler=new SceneMediaCompiler(new SceneMediaCompiler.Limits(8,1000,work.predicates()));
        assertEquals(1,compiler.prepare(source,DOMAIN).work().compiledVolumes());
        var box=OrientedMediumFixtures.scene(false,0);
        assertThrows(IllegalStateException.class,()->compiler.prepare(box,SceneMediaCompilerTest.domain()));
        assertEquals(1,compiler.prepare(source,DOMAIN).work().reusedVolumes());
        var altered=TETRA.stream().map(t->t.stream().map(v->new SceneInputs.Vec3(v.x()*.5f,v.y(),v.z())).toList()).toList();
        var changed=compiler.prepare(scene(altered),DOMAIN);assertEquals(4,changed.work().triangles());assertEquals(1,changed.work().compiledVolumes());
        assertEquals(0,changed.classify(SceneMediaCompilerTest.at(new SceneInputs.Origin(1.5,65,1))).initialMedia().orElseThrow().enclosures().size());
    }
    @Test void selfCrossingFaceCannotPassUsingOnlyConsistentLocalTurns() {
        var box=OrientedMediumFixtures.scene(false,0).geometry().getFirst().input();
        var faces=new ArrayList<List<SceneInputs.Vec3>>();
        for(var p:box.primitives())if(p.ordinal()!=4) {
            var c=p.corners();faces.add(List.of(c.get(0).position(),c.get(1).position(),c.get(2).position()));faces.add(List.of(c.get(0).position(),c.get(2).position(),c.get(3).position()));
        }
        var ring=List.of(new SceneInputs.Vec3(0,16,-1),new SceneInputs.Vec3(-16,4,-1),new SceneInputs.Vec3(-8,-16,-1),new SceneInputs.Vec3(8,-16,-1),new SceneInputs.Vec3(16,4,-1));
        var center=new SceneInputs.Vec3(0,0,-1);
        for(int i=0;i<5;i++)faces.add(List.of(center,ring.get(i*2%5),ring.get((i+1)*2%5)));
        var failure=assertThrows(IllegalArgumentException.class,()->new SceneMediaCompiler().prepare(scene(faces),DOMAIN));
        assertTrue(failure.getMessage().startsWith("Automatic medium classification:"));
    }
    @Test void filteredPlaneSignsAgreeWithIndependentExactDeterminants() {
        var random=new java.util.Random(77192);var budget=new ConvexVolume.Budget(10000);
        for(int test=0;test<500;test++) {
            double scale=Math.scalb(1.0,test%81-40),offset=test%2==0?0:30_000_000;
            var a=new ConvexVolume.Point(offset+random.nextInt(16)*scale,offset+random.nextInt(16)*scale,offset+random.nextInt(16)*scale);
            var b=new ConvexVolume.Point(a.x()+scale,a.y()+3*scale,a.z()+2*scale);
            var c=new ConvexVolume.Point(a.x()+2*scale,a.y()-scale,a.z()+scale);
            var p=new ConvexVolume.Point(b.x()+c.x()-a.x(),b.y()+c.y()-a.y(),b.z()+c.z()-a.z());
            if(test%3==1)p=new ConvexVolume.Point(p.x(),p.y(),Math.nextUp(p.z()));
            if(test%3==2)p=new ConvexVolume.Point(p.x(),p.y(),Math.nextDown(p.z()));

            var matrix=new BigDecimal[4][4];int row=0;
            for(var point:List.of(a,b,c,p)) { matrix[row++]=new BigDecimal[]{BigDecimal.ONE,new BigDecimal(point.x()),new BigDecimal(point.y()),new BigDecimal(point.z())}; }
            assertEquals(determinant(matrix).signum(),ConvexVolume.side(new ConvexVolume.Triangle(a,b,c),p,budget),"predicate sample "+test);
        }
    }
    private static BigDecimal determinant(BigDecimal[][] m) {
        if(m.length==1)return m[0][0];var result=BigDecimal.ZERO;
        for(int column=0;column<m.length;column++) {
            var minor=new BigDecimal[m.length-1][m.length-1];
            for(int r=1;r<m.length;r++) { int k=0;for(int c=0;c<m.length;c++)if(c!=column)minor[r-1][k++]=m[r][c]; }
            var term=m[0][column].multiply(determinant(minor));result=column%2==0?result.add(term):result.subtract(term);
        }
        return result;
    }
    private static SceneInputs.Vec3 mid(SceneInputs.Vec3 a,SceneInputs.Vec3 b) { return new SceneInputs.Vec3((a.x()+b.x())*.5f,(a.y()+b.y())*.5f,(a.z()+b.z())*.5f); }
    private static SceneStore.Revision scene(List<List<SceneInputs.Vec3>> triangles) {
        var source=OrientedMediumFixtures.scene(false,0).geometry().getFirst().input();var template=source.primitives().getFirst();
        var primitives=new ArrayList<SceneInputs.Primitive>();long ordinal=0;
        for(var triangle:triangles) {
            var corners=triangle.stream().map(p->new SceneInputs.Corner(p,0,0,template.corners().getFirst().tint(),new SceneInputs.Vec3(0,0,1),new SceneInputs.Vec3(1,0,0))).toList();
            primitives.add(new SceneInputs.Primitive(template.part(),ordinal++,template.surface(),corners));
        }
        var compiled=new SceneCompiler().compile(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),false,source.motion(),source.participation(),primitives));
        return new SceneStore.Revision(1,1,List.of(compiled),compiled.bytes());
    }

    static SceneStore.Revision tapered(SceneStore.Revision scene) {
        var meshes=new ArrayList<SceneCompiler.Compiled>();
        for(var mesh:scene.geometry()) {
            var source=mesh.input();
            if(source.primitives().stream().noneMatch(p->p.surface().boundary()==SceneInputs.Boundary.NESTED_VOLUME)) { meshes.add(mesh);continue; }
            var primitives=source.primitives().stream().map(p->{
                var positions=p.corners().stream().map(c->{ var v=c.position();return new org.joml.Vector3f(v.x()*(v.y()>0?.5f:1),v.y(),v.z()); }).toList();
                var tangent=new org.joml.Vector3f(positions.get(1)).sub(positions.get(0)).normalize();
                var normal=new org.joml.Vector3f(tangent).cross(new org.joml.Vector3f(positions.get(2)).sub(positions.get(0))).normalize();
                var corners=new ArrayList<SceneInputs.Corner>();
                for(int i=0;i<positions.size();i++) { var v=positions.get(i);var c=p.corners().get(i);corners.add(new SceneInputs.Corner(new SceneInputs.Vec3(v.x,v.y,v.z),c.u(),c.v(),c.tint(),new SceneInputs.Vec3(normal.x,normal.y,normal.z),new SceneInputs.Vec3(tangent.x,tangent.y,tangent.z))); }
                return new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners);
            }).toList();
            meshes.add(new SceneCompiler().compile(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),false,source.motion(),source.participation(),primitives)));
        }
        return new SceneStore.Revision(scene.serial(),scene.world(),meshes,meshes.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
}
