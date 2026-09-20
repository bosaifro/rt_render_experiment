package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.engine.abi.R2Abi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MediumContractTest {
    @Test void neutralBoundariesAndPublicationIdentity() {
        var key=new SceneInputs.Key(0,0);
        var unqualified=new SceneInputs.Surface(key,24,0,key,key,SceneInputs.Coverage.DIELECTRIC,.5f,true,1,0,0,
            MaterialInputs.Layers.plain(key),SceneInputs.HostOcclusion.PASS);
        assertEquals(SceneInputs.Boundary.UNQUALIFIED,unqualified.boundary(),"A unqualified water/material hint must not become a certified volume");
        assertThrows(IllegalArgumentException.class,()->unqualified.withNestedVolume(0));
        var old=SceneContractsTest.fixture(1);var primitive=old.primitives().getFirst();
        assertThrows(IllegalArgumentException.class,()->primitive.surface().withNestedVolume(17));
        var first=unqualified.withNestedVolume(0x100000001L);var second=unqualified.withNestedVolume(0x200000001L);
        var compiler=new SceneCompiler();
        var a=compiler.compile(new SceneInputs.Geometry(old.key(),old.revision(),old.origin(),old.current(),old.previous(),true,
            old.motion(),old.participation(),List.of(new SceneInputs.Primitive(primitive.part(),0,first,primitive.corners()))));
        var b=compiler.compile(new SceneInputs.Geometry(old.key(),old.revision(),old.origin(),old.current(),old.previous(),true,
            old.motion(),old.participation(),List.of(new SceneInputs.Primitive(primitive.part(),0,second,primitive.corners()))));
        assertEquals(first,a.parts().getFirst().surface());assertEquals(second,b.parts().getFirst().surface());
        assertEquals(a.topologyHash(),b.topologyHash());assertEquals(a.positionHash(),b.positionHash());
        assertNotEquals(a.appearanceHash(),b.appearanceHash(),"Changed boundary identity must invalidate its immutable hit-data publication");
        assertEquals(-1,a.positions().mismatch(b.positions()));assertEquals(-1,a.indices().mismatch(b.indices()));
        var bytes=java.nio.ByteBuffer.allocate(R2Abi.SurfaceRecord.SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        R2Abi.SurfaceRecord.medium(bytes,0,second.medium());R2Abi.SurfaceRecord.boundary(bytes,0,R2Abi.ROLE_VOLUME);
        assertEquals(second.medium(),bytes.getLong(R2Abi.SurfaceRecord.MEDIUM));
        assertEquals(R2Abi.ROLE_VOLUME,bytes.getInt(R2Abi.SurfaceRecord.BOUNDARY));
        assertEquals(128,R2Abi.SurfaceRecord.SIZE,"Boundary tag uses former explicit padding; existing record size is retained");
    }
}
