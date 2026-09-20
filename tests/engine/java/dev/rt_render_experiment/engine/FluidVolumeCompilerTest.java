package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.FluidInputs;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FluidVolumeCompilerTest {
    record Cell(int x,int y,int z) {}
    static SceneStore.Revision source(Set<Cell> cells,boolean separateMeshes) {
        var keys=cells.stream().sorted(java.util.Comparator.comparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z)).toList();
        var compiled=new ArrayList<SceneCompiler.Compiled>();var all=new ArrayList<FluidInputs.Cell>();int ordinal=0;
        for(var p:keys) {
            var key=new SceneInputs.Key(4501,++ordinal);var neutral=new SceneInputs.Key(0,0);
            var surface=new SceneInputs.Surface(key,8,0,neutral,neutral,SceneInputs.Coverage.DIELECTRIC,.5f,true,1,0,0,MaterialInputs.Layers.plain(neutral),SceneInputs.HostOcclusion.PASS);
            var samples=new ArrayList<FluidInputs.Sample>();
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                var q=new Cell(p.x+dx,p.y,p.z+dz);boolean same=cells.contains(q);
                samples.add(new FluidInputs.Sample(same?8f/9:0,same,cells.contains(new Cell(q.x,q.y+1,q.z)),false));
            }
            int mask=0;int[][] directions={{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
            for(int face=0;face<6;face++) { var d=directions[face];if(cells.contains(new Cell(p.x+d[0],p.y+d[1],p.z+d[2])))mask|=1<<face; }
            var position=separateMeshes?new SceneInputs.Vec3(0,0,0):new SceneInputs.Vec3(p.x,p.y,p.z);
            var cell=new FluidInputs.Cell(key,0,position,samples,63&~mask,0,0,0,new SceneInputs.Color(.3f,.55f,.8f,1),surface,surface,surface,
                java.util.Collections.nCopies(6,FluidInputs.FaceCoverage.CLEAR),new FluidInputs.VolumeFacts(FluidInputs.VolumeShape.HEIGHT_FIELD_CELL,mask));
            all.add(cell);
            if(separateMeshes)compiled.add(compile(new SceneInputs.Key(4500,ordinal),new SceneInputs.Origin(p.x,64+p.y,p.z),List.of(cell)));
        }
        if(!separateMeshes)compiled.add(compile(new SceneInputs.Key(4500,1),new SceneInputs.Origin(0,64,0),all));
        return new SceneStore.Revision(1,1,compiled,compiled.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
    private static SceneCompiler.Compiled compile(SceneInputs.Key key,SceneInputs.Origin origin,List<FluidInputs.Cell> cells) {
        return new SceneCompiler().compile(new SceneInputs.Geometry(key,new SceneInputs.Revision(1,1,0,1,1,1,1),origin,SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),false,
            SceneInputs.Motion.STATIC,new Participation(Participation.WORLD,true),List.of(),cells));
    }
    static FrameScene frame(long serial,SceneStore.Revision scene) { return new FrameScene(serial,scene,null,List.of(),LightInputs.Publication.empty()).withPreparedVolumes(); }
    @Test void connectedSourceCellsRetainGeometryAndUseOneRegion() {
        for(boolean separate:new boolean[]{false,true})for(var cells:List.of(Set.of(new Cell(0,0,-2)),Set.of(new Cell(0,0,-2),new Cell(1,0,-2)),Set.of(new Cell(0,0,-2),new Cell(0,1,-2)))) {
            var source=source(cells,separate);var compiler=new ModelVolumeCompiler();var result=compiler.prepare(frame(1,source));
            assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().detail());
            assertEquals(0,result.report().volumes());assertEquals(1,result.report().fluidRegions());assertEquals(cells.size(),result.report().fluidCells());assertEquals(1,result.scene().mediumDomain().orElseThrow().volumes().size());
            for(int i=0;i<source.geometry().size();i++) {
                var a=source.geometry().get(i);var b=result.scene().revision().geometry().get(i);
                assertSame(a.input(),b.input());assertEquals(-1,a.positions().mismatch(b.positions()));assertEquals(-1,a.corners().mismatch(b.corners()));assertEquals(-1,a.indices().mismatch(b.indices()));
                assertEquals(a.topologyHash(),b.topologyHash());assertTrue(b.parts().stream().allMatch(p->p.surface().boundary()==SceneInputs.Boundary.NESTED_VOLUME));
            }
            var next=compiler.prepare(frame(2,source));assertTrue(next.report().reused());assertEquals(0,next.report().geometricWork());
            assertEquals(result.scene().mediumDomain(),next.scene().mediumDomain());
        }
    }
    @Test void declarationsNeighboursAndPressureRefusePartialPublication() {
        var source=source(Set.of(new Cell(0,0,-2),new Cell(1,0,-2)),true);
        var missing=new SceneStore.Revision(1,1,source.geometry().subList(0,1),source.bytes());var input=frame(1,missing);
        var failure=new ModelVolumeCompiler().prepare(input);assertEquals(ModelVolumeCompiler.Status.INCOMPLETE_FLUID,failure.report().status());assertSame(input,failure.scene());
        var g=source.geometry().getFirst().input();var c=g.fluids().getFirst();
        var unqualified=new FluidInputs.Cell(c.part(),c.ordinal(),c.position(),c.neighborhood(),c.candidateFaces(),c.overlayFaces(),c.flowX(),c.flowZ(),c.tint(),c.still(),c.flowing(),c.overlay(),c.neighbors());
        assertEquals(FluidInputs.VolumeFacts.UNKNOWN,unqualified.volume());
        var unsupported=compile(g.key(),g.origin(),List.of(unqualified));
        assertEquals(ModelVolumeCompiler.Status.UNSUPPORTED_FLUID,new ModelVolumeCompiler().prepare(frame(1,new SceneStore.Revision(1,1,List.of(unsupported),unsupported.bytes()))).report().status());
        var low=new ModelVolumeCompiler(new ModelVolumeCompiler.Limits(8,100,100,1));assertEquals(ModelVolumeCompiler.Status.PRESSURE,low.prepare(frame(1,source)).report().status());
        var modelsOnly=new FrameScene(1,source,null,List.of(),LightInputs.Publication.empty()).withModelVolumes();
        assertEquals(ModelVolumeCompiler.Status.INCOMPLETE_MODEL,new ModelVolumeCompiler().prepare(modelsOnly).report().status());
        assertThrows(IllegalArgumentException.class,()->new FluidInputs.VolumeFacts(FluidInputs.VolumeShape.HEIGHT_FIELD_CELL,64));
    }
    @Test void zeroTriangleInteriorCellsAreStillConnectedAndBounded() {
        var cells=new java.util.HashSet<Cell>();for(int x=0;x<3;x++)for(int y=0;y<3;y++)for(int z=0;z<3;z++)cells.add(new Cell(x,y,z));
        var source=source(cells,true);assertTrue(source.geometry().stream().anyMatch(g->g.triangleCount()==0));
        var grouped=FluidVolumeCompiler.prepare(source.geometry(),27);
        assertEquals(27,grouped.cells());assertEquals(1,grouped.regions().size());assertEquals(27,grouped.regions().getFirst().cells());

        var input=frame(1,source);var result=new ModelVolumeCompiler().prepare(input);
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status());
        assertTrue(result.scene().mediumDomain().isPresent());
        for(int i=0;i<source.geometry().size();i++)assertSame(source.geometry().get(i).input(),result.scene().revision().geometry().get(i).input());
    }
    @Test void disjointRegionsAndClosedModelsPublishTogether() {
        var fluid=source(Set.of(new Cell(100,0,0)),false);var model=ModelVolumeCompilerTest.source(false);var geometry=new ArrayList<>(model.geometry());geometry.addAll(fluid.geometry());
        var result=new ModelVolumeCompiler().prepare(frame(1,new SceneStore.Revision(1,1,geometry,model.bytes()+fluid.bytes())));
        assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().detail());assertEquals(2,result.report().volumes());assertEquals(1,result.report().fluidRegions());
        assertEquals(3,result.scene().mediumDomain().orElseThrow().volumes().size());
    }
    @Test void inconsistentAdjacencyRenderedInternalFaceAndMissingExteriorRefuse() {
        for(int defect=0;defect<3;defect++) {
            var source=source(defect==2?Set.of(new Cell(0,0,0)):Set.of(new Cell(0,0,0),new Cell(1,0,0)),true);
            var g=source.geometry().getFirst().input();var c=g.fluids().getFirst();
            var changed=new FluidInputs.Cell(c.part(),c.ordinal(),c.position(),c.neighborhood(),
                defect==1?c.candidateFaces()|32:defect==2?c.candidateFaces()&~1:c.candidateFaces(),c.overlayFaces(),c.flowX(),c.flowZ(),c.tint(),c.still(),c.flowing(),c.overlay(),c.neighbors(),
                defect==0?new FluidInputs.VolumeFacts(FluidInputs.VolumeShape.HEIGHT_FIELD_CELL,0):c.volume());
            var meshes=new ArrayList<>(source.geometry());meshes.set(0,compile(g.key(),g.origin(),List.of(changed)));
            var input=frame(1,new SceneStore.Revision(2,1,meshes,source.bytes()));var result=new ModelVolumeCompiler().prepare(input);
            assertEquals(switch(defect) { case 0 -> ModelVolumeCompiler.Status.INCOMPLETE_FLUID;case 1 -> ModelVolumeCompiler.Status.HIDDEN_INTERFACE;
                default -> ModelVolumeCompiler.Status.HIDDEN_INTERFACE; },result.report().status(),result.report().detail());
            assertSame(input,result.scene());assertTrue(input.mediumDomain().isEmpty());
        }
    }
    @Test void sourceAnchorIdentitySurvivesRegionGrowthAndSplit() {
        var compiler=new ModelVolumeCompiler();
        long first=compiler.prepare(frame(1,source(Set.of(new Cell(0,0,0)),true))).scene().mediumDomain().orElseThrow().volumes().getFirst().identity();
        var grown=compiler.prepare(frame(2,source(Set.of(new Cell(0,0,0),new Cell(1,0,0)),true)));
        assertEquals(first,grown.scene().mediumDomain().orElseThrow().volumes().getFirst().identity());
        var split=compiler.prepare(frame(3,source(Set.of(new Cell(0,0,0),new Cell(2,0,0)),true)));
        assertEquals(2,split.report().fluidRegions());assertEquals(first,split.scene().mediumDomain().orElseThrow().volumes().getFirst().identity());
        assertNotEquals(first,split.scene().mediumDomain().orElseThrow().volumes().getLast().identity());
    }
    @Test void rebasedCellsJoinAtLargeCoordinatesAndUnqualifiedPlacementRefuses() {
        var source=source(Set.of(new Cell(0,0,0),new Cell(1,0,0)),true);
        for(double displacement:new double[]{-30_000_000,30_000_000}) {
            var meshes=new ArrayList<SceneCompiler.Compiled>();
            for(var mesh:source.geometry()) {
                var g=mesh.input();meshes.add(compile(g.key(),new SceneInputs.Origin(g.origin().x()+displacement,g.origin().y(),g.origin().z()+displacement),g.fluids()));
            }
            var result=new ModelVolumeCompiler().prepare(frame(1,new SceneStore.Revision(1,1,meshes,source.bytes())));
            assertEquals(ModelVolumeCompiler.Status.READY,result.report().status(),result.report().detail());
            var origin=new SceneInputs.Origin(displacement+.5,64.35,displacement+.5);
            var classified=new SceneMediaCompiler().prepare(result.scene().revision(),result.scene().mediumDomain().orElseThrow()).classify(SceneMediaCompilerTest.at(origin));
            assertEquals(1,classified.initialMedia().orElseThrow().enclosures().size());
        }
        var g=source.geometry().getFirst().input();var rows=SceneInputs.Transform.identity().rows();rows[0]=-1;
        var reflected=new SceneCompiler().compile(new SceneInputs.Geometry(g.key(),g.revision(),g.origin(),new SceneInputs.Transform(rows),g.previous(),false,g.motion(),g.participation(),g.primitives(),g.fluids()));
        var input=frame(1,new SceneStore.Revision(1,1,List.of(reflected),reflected.bytes()));var result=new ModelVolumeCompiler().prepare(input);
        assertEquals(ModelVolumeCompiler.Status.UNSUPPORTED_FLUID,result.report().status());assertSame(input,result.scene());
    }
}
