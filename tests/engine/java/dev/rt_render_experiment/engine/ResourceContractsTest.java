package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;
import dev.rt_render_experiment.engine.abi.R2Abi;
import static org.junit.jupiter.api.Assertions.*;

final class ResourceContractsTest {
    @org.junit.jupiter.api.Test void collapsedEmitterTrianglesRetainValidOpticalArea() {
        var base=SceneFixtures.prepare(false).geometry().getFirst().input();var primitive=base.primitives().getFirst();
        var light=new LightInputs.Area(new SceneInputs.Key(22,1),1,LightInputs.Emission.areaImportance(new SceneInputs.Vec3(1,1,1)),base.origin(),0,
            LightInputs.Stratum.OBJECT,base.key(),primitive.part(),primitive.ordinal());
        for(boolean collapsed:new boolean[]{false,true}) {
            var corners=new java.util.ArrayList<>(primitive.corners());corners.set(1,corners.getFirst());
            if(collapsed)for(int i=2;i<corners.size();i++)corners.set(i,corners.getFirst());
            var mesh=new SceneCompiler().compile(new SceneInputs.Geometry(base.key(),base.revision(),base.origin(),base.current(),base.previous(),false,
                base.motion(),base.participation(),List.of(new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),primitive.surface(),corners))));
            var result=new LightCompiler().compile(List.of(mesh),List.of(light),base.origin());
            var data=result.sources().order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(collapsed?0:2,data.getFloat(R2Abi.SourceRecord.NORMALAREA+12));
            for(int offset=0;offset<R2Abi.SourceRecord.IMPORTANCE;offset+=4)assertTrue(Float.isFinite(data.getFloat(offset)));
            assertEquals(1,result.associations().size());
            assertEquals(2,data.getInt(R2Abi.SourceRecord.ASSOCIATION+8),"The original primitive keeps both triangle slots");
            if(!collapsed)assertEquals(1,data.getFloat(R2Abi.SourceRecord.NORMALAREA+8),"Orientation must come from the nondegenerate sibling");
        }
    }
    @org.junit.jupiter.api.Test void completeResourcesAndIndependentSourceIdentity() {
        var importance=LightInputs.Emission.areaImportance(new SceneInputs.Vec3(1,0.5f,0.25f));
        assertEquals(1,importance.strength());
        assertThrows(IllegalArgumentException.class,()->LightInputs.Emission.areaImportance(new SceneInputs.Vec3(0,0,0)));
        var bytes=ByteBuffer.wrap(new byte[]{0,0,0,0,-1,-1,-1,-1});
        var source=new TextureInputs.Texture(new SceneInputs.Key(1,7),1,TextureInputs.Encoding.SRGB,TextureInputs.Address.CLAMP,
            List.of(new TextureInputs.Level(2,1,bytes)));
        bytes.put(0,(byte)32);
        var compiled=new TextureCompiler().compile(source);
        assertEquals(0,compiled.levels().getFirst().pixels().get(0));
        assertEquals(2,compiled.levels().size());
        assertEquals(188,Byte.toUnsignedInt(compiled.levels().getLast().pixels().get(0)));
        assertEquals(128,Byte.toUnsignedInt(compiled.levels().getLast().pixels().get(3)));
        var lights=new LightCompiler();
        var origin=new SceneInputs.Origin(0,0,0);
        var a=point(10,new SceneInputs.Origin(-0.1,0,0));
        var first=lights.compile(List.of(),new LightInputs.Publication(1,List.of(a)),origin);
        int initialToken=first.sources().order(ByteOrder.LITTLE_ENDIAN).getInt(R2Abi.SourceRecord.ASSOCIATION+12);
        var b=point(20,new SceneInputs.Origin(-32,0,0));
        var moved=lights.compile(List.of(),new LightInputs.Publication(2,List.of(b,a)),origin);
        assertEquals(initialToken,moved.sources().order(ByteOrder.LITTLE_ENDIAN).getInt(R2Abi.SourceRecord.SIZE+R2Abi.SourceRecord.ASSOCIATION+12));
        assertEquals(-2,moved.cells().order(ByteOrder.LITTLE_ENDIAN).getInt(0));
        assertEquals(-1,moved.cells().order(ByteOrder.LITTLE_ENDIAN).getInt(R2Abi.LightCellRecord.SIZE));
        assertEquals(2,moved.worldCount());
        assertThrows(IllegalArgumentException.class,()->lights.compile(List.of(),new LightInputs.Publication(3,List.of(a,a)),origin));
        var geometry=SceneFixtures.prepare(false).geometry();
        var g=geometry.getFirst().input(); var part=g.primitives().getFirst();
        var area=new LightInputs.Area(new SceneInputs.Key(21,7),1,new LightInputs.Emission(5,1,new SceneInputs.Vec3(0,0,0)),g.origin(),0,
            LightInputs.Stratum.WORLD,g.key(),part.part(),part.ordinal());
        var areaCompiled=lights.compile(geometry,new LightInputs.Publication(4,List.of(area)),g.origin());
        assertEquals(4,areaCompiled.sources().order(ByteOrder.LITTLE_ENDIAN).getFloat(R2Abi.SourceRecord.NORMALAREA+12));
        assertEquals(1,areaCompiled.associations().size());
        assertThrows(IllegalArgumentException.class,()->lights.compile(List.of(),new LightInputs.Publication(4,List.of(area)),g.origin()));
    }
    private static LightInputs.Point point(int identity,SceneInputs.Origin position) {
        return new LightInputs.Point(new SceneInputs.Key(1,identity),1,new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0)),position,0,
            LightInputs.Stratum.WORLD,position,0.5f);
    }
}
