package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.FluidInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import static org.junit.jupiter.api.Assertions.*;

final class FluidCompilerTest {
    @org.junit.jupiter.api.Test void preparedCoverageAndOneCompiledRepresentation() {
        var key=new SceneInputs.Key(1,2);var neutral=new SceneInputs.Key(0,0);
        var surface=new SceneInputs.Surface(key,8,0,neutral,neutral,SceneInputs.Coverage.DIELECTRIC,.5f,true,1,0,0,MaterialInputs.Layers.plain(neutral),SceneInputs.HostOcclusion.PASS);
        var full=coverage(new FluidInputs.Rectangle(0,0,1,1));
        var half=coverage(new FluidInputs.Rectangle(0,0,1,.5));
        var tiled=coverage(new FluidInputs.Rectangle(0,0,.5,.5),new FluidInputs.Rectangle(.5,0,1,.5));
        var hole=coverage(new FluidInputs.Rectangle(0,0,.49,1),new FluidInputs.Rectangle(.51,0,1,1));
        var raised=coverage(new FluidInputs.Rectangle(0,.1,1,1));
        var neighbors=List.of(full,full,half,tiled,hole,raised);
        var samples=java.util.Collections.nCopies(9,new FluidInputs.Sample(.5f,true,false,false));
        var cell=new FluidInputs.Cell(key,0,new SceneInputs.Vec3(-16,0,-16),samples,63,0,0,0,new SceneInputs.Color(1,1,1,1),surface,surface,surface,neighbors);
        var compiler=new FluidCompiler();int expected=(1<<FluidInputs.Face.UP.ordinal())|(1<<FluidInputs.Face.WEST.ordinal())|(1<<FluidInputs.Face.EAST.ordinal());
        assertEquals(expected,compiler.admittedFaces(cell));
        var preAdmitted=new FluidInputs.Cell(key,0,cell.position(),samples,expected,0,0,0,cell.tint(),surface,surface,surface);
        assertEquals(compiler.compile(preAdmitted),compiler.compile(cell),"Changing the source representation must preserve final corners and faces");
        assertTrue(FluidCompiler.occluded(full,FluidInputs.Face.UP,1));
        assertFalse(FluidCompiler.occluded(full,FluidInputs.Face.UP,.5f),"A ceiling cannot hide the lower free surface");
        assertFalse(FluidCompiler.occluded(hole,FluidInputs.Face.NORTH,.5f));
        assertFalse(FluidCompiler.occluded(raised,FluidInputs.Face.NORTH,.5f));
        var precise=coverage(new FluidInputs.Rectangle(0,0,1,.5));
        var sourcePrecision=new FluidInputs.FaceCoverage(precise.rectangles(),1e-7);
        assertFalse(FluidCompiler.occluded(precise,FluidInputs.Face.NORTH,Math.nextUp(.5f)));
        assertTrue(FluidCompiler.occluded(sourcePrecision,FluidInputs.Face.NORTH,Math.nextUp(.5f)));
        assertFalse(FluidCompiler.occluded(sourcePrecision,FluidInputs.Face.NORTH,Math.nextUp(Math.nextUp(.5f))));
        assertThrows(IllegalArgumentException.class,()->new FluidInputs.Rectangle(0,0,Double.NaN,1));
        assertThrows(IllegalArgumentException.class,()->new FluidInputs.FaceCoverage(List.of(),Double.POSITIVE_INFINITY));
        var mutable=new java.util.ArrayList<>(full.rectangles());var copy=new FluidInputs.FaceCoverage(mutable,0);mutable.clear();assertEquals(full,copy);
        var input=new SceneInputs.Geometry(key,new SceneInputs.Revision(1,1,0,0,1,1,1),new SceneInputs.Origin(0,0,0),SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),false,
            SceneInputs.Motion.STATIC,new dev.rt_render_experiment.contract.Participation(15,true),List.of(),List.of(cell));
        var request=new SceneInputs.PreparationRequest(1,key,input.revision(),8192);
        var undercharged=SceneInputs.PreparedSource.geometryOnly(new SceneInputs.PreparationResult(request,SceneInputs.PreparationStatus.READY,List.of(input),cell.occlusionBytes()-1,"Under-reported prepared faces"));
        assertThrows(IllegalArgumentException.class,()->SceneStore.workBytes(undercharged),"A producer must not bypass queue pressure with uncharged coverage");
    }
    private static FluidInputs.FaceCoverage coverage(FluidInputs.Rectangle... rectangles) { return new FluidInputs.FaceCoverage(List.of(rectangles),0); }
    @org.junit.jupiter.api.Test void sweepMatchesIndependentDiscreteCoverageAndRetainsSmallHoles() {
        var random=new java.util.Random(8123);
        for(int trial=0;trial<200;trial++) {
            boolean[][] cells=new boolean[16][16];var rectangles=new java.util.ArrayList<FluidInputs.Rectangle>();
            for(int r=0;r<32;r++) {
                int x=random.nextInt(16),y=random.nextInt(16),endX=x+1+random.nextInt(16-x),endY=y+1+random.nextInt(16-y);
                rectangles.add(new FluidInputs.Rectangle(x/16.0,y/16.0,endX/16.0,endY/16.0));
                for(int i=x;i<endX;i++)for(int j=y;j<endY;j++)cells[i][j]=true;
            }
            for(int h:new int[]{4,9,16}) {
                boolean expected=true;for(int i=0;i<16;i++)for(int j=0;j<h;j++)expected&=cells[i][j];
                assertEquals(expected,FluidCompiler.occluded(new FluidInputs.FaceCoverage(rectangles,0),FluidInputs.Face.NORTH,h/16f));
                assertEquals(expected,FluidCompiler.occluded(new FluidInputs.FaceCoverage(rectangles.reversed(),0),FluidInputs.Face.NORTH,h/16f));
            }
        }
        var tiled=new java.util.ArrayList<FluidInputs.Rectangle>();
        for(int x=0;x<32;x++)for(int y=0;y<32;y++)tiled.add(new FluidInputs.Rectangle(x/32.0,y/32.0,(x+1)/32.0,(y+1)/32.0));
        assertTrue(FluidCompiler.occluded(new FluidInputs.FaceCoverage(tiled,0),FluidInputs.Face.EAST,1));
        tiled.remove(11*32+9);
        assertFalse(FluidCompiler.occluded(new FluidInputs.FaceCoverage(tiled,0),FluidInputs.Face.EAST,1));
        assertEquals(new FluidInputs.Rectangle(0,0,1,1),new FluidInputs.Rectangle(-0.0,-0.0,1,1));
    }
    @org.junit.jupiter.api.Test void closedInterfacesAndPreparedHeightLaw() {
        var key=new SceneInputs.Key(1,1);
        var surface=new SceneInputs.Surface(key,8,0,new SceneInputs.Key(0,0),new SceneInputs.Key(0,0),SceneInputs.Coverage.DIELECTRIC,0,true,1,0,0,MaterialInputs.Layers.plain(new SceneInputs.Key(0,0)),SceneInputs.HostOcclusion.PASS);
        var neighborhood=new java.util.ArrayList<>(java.util.Collections.nCopies(9,new FluidInputs.Sample(8f/9,true,false,false)));
        var cell=new FluidInputs.Cell(key,0,new SceneInputs.Vec3(0,0,0),neighborhood,63,0,0,0,new SceneInputs.Color(1,1,1,1),surface,surface,surface);
        var compiler=new FluidCompiler(); var faces=compiler.compile(cell);
        assertEquals(6,faces.size());
        assertEquals(8f/9,compiler.heights(cell).northWest(),1e-7);
        var edgeCounts=new java.util.HashMap<String,Integer>();
        for(var face:faces)for(int i=0;i<4;i++) {
            String a=face.corners().get(i).position().toString(),b=face.corners().get((i+1)%4).position().toString();
            String edge=a.compareTo(b)<0?a+"|"+b:b+"|"+a; edgeCounts.merge(edge,1,Integer::sum);
        }
        assertTrue(edgeCounts.values().stream().allMatch(count->count==2),"Optical shell must have no raster-offset cracks");
        neighborhood.set(0,new FluidInputs.Sample(1,true,true,false));
        var raised=new FluidInputs.Cell(key,0,cell.position(),neighborhood,63,0,1,0,cell.tint(),surface,surface,surface);
        assertEquals(1,compiler.heights(raised).northWest());
        assertTrue(compiler.compile(raised).getFirst().corners().stream().allMatch(c->Float.isFinite(c.u())&&Float.isFinite(c.v())));
        var noInternal=new FluidInputs.Cell(key,0,cell.position(),List.copyOf(neighborhood),63 & ~(1<<FluidInputs.Face.EAST.ordinal()),0,0,0,cell.tint(),surface,surface,surface);
        assertEquals(5,compiler.compile(noInternal).size());
        var geometry=new SceneInputs.Geometry(key,new SceneInputs.Revision(1,1,0,0,1,1,1),new SceneInputs.Origin(0,0,0),
            SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),false,SceneInputs.Motion.STATIC,
            new dev.rt_render_experiment.contract.Participation(dev.rt_render_experiment.contract.Participation.WORLD,true),List.of(),List.of(cell));
        var production=new SceneCompiler().compile(geometry);
        assertEquals(12,production.triangleCount(),"Production scene compilation must consume fluid facts");
    }
}
