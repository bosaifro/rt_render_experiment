package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.engine.abi.R2Abi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class MediumDefinitionsTest {
    @Test void exactIdentityEncodingAndContentReuse() {
        var scene=OrientedMediumFixtures.scene(false,0);var domain=SceneMediaCompilerTest.domain();
        var definitions=MediumDefinitions.prepare(domain,scene.geometry(),null);var bytes=definitions.bytes();
        assertEquals(48,R2Abi.MediumRecord.SIZE);assertEquals(96,bytes.remaining());
        assertEquals(128,R2Abi.SurfaceRecord.SIZE);assertEquals(104,R2Abi.SurfaceRecord.MEDIUMDEFINITION);
        assertEquals(0x100000001L,bytes.getLong(0));assertEquals(0x200000001L,bytes.getLong(48));
        assertEquals(2,bytes.getInt(8));assertEquals(0,bytes.getInt(12));assertEquals(1.5f,bytes.getFloat(16));
        assertEquals(domain.volumes().getFirst().medium().absorption().x(),bytes.getFloat(20));
        assertEquals(0,bytes.getFloat(44));assertEquals(48,definitions.offset(0x200000001L));
        assertSame(definitions,MediumDefinitions.prepare(new MediumInputs.Domain(List.of(domain.volumes().get(1),domain.volumes().get(0))),scene.geometry(),definitions));
        assertThrows(java.nio.ReadOnlyBufferException.class,()->bytes.putInt(0,0));
        assertThrows(IllegalArgumentException.class,()->definitions.offset(10));
        assertThrows(IllegalArgumentException.class,()->MediumDefinitions.prepare(MediumDefinitionFixtures.domain(false),scene.geometry(),definitions));
        assertNull(MediumDefinitions.prepare(null,scene.geometry(),definitions));
    }
    @Test void originMustAgreeWithTheGpuPublication() {
        var scene=MediumDefinitionFixtures.scene();var domain=MediumDefinitionFixtures.domain(true);
        var definitions=MediumDefinitions.prepare(domain,scene.geometry(),null);
        var frame=new SceneMediaCompiler().prepare(scene,domain).classify(InitialMediaFixtures.frame());
        definitions.requireFrameOrigin(frame);
        assertThrows(IllegalArgumentException.class,()->definitions.requireFrameOrigin(InitialMediaFixtures.frame()));
        var mismatch=frame.withInitialMedia(new MediumInputs.Origin(1,1,frame.eye(),.4f,GlassFixtures.glass().subList(0,1)));
        assertThrows(IllegalArgumentException.class,()->definitions.requireFrameOrigin(mismatch));
    }
    @Test void coefficientChangeReusesCertificationWithoutChangingOlderPreparedFacts() {
        var scene=MediumDefinitionFixtures.scene();var compiler=new SceneMediaCompiler();
        var first=compiler.prepare(scene,MediumDefinitionFixtures.domain(false));
        var changed=compiler.prepare(scene,MediumDefinitionFixtures.domain(true));
        assertEquals(0,changed.work().compiledVolumes());assertEquals(1,changed.work().reusedVolumes());assertEquals(0,changed.work().predicates());
        var frame=InitialMediaFixtures.frame();
        assertEquals(MediumDefinitionFixtures.domain(false).volumes().getFirst().medium(),first.classify(frame).initialMedia().orElseThrow().enclosures().getFirst().medium());
        assertEquals(MediumDefinitionFixtures.domain(true).volumes().getFirst().medium(),changed.classify(frame).initialMedia().orElseThrow().enclosures().getFirst().medium());
    }
    @Test void domainHistoryTracksSubmissionRatherThanCandidates() {
        var frame=MediumDefinitionFixtures.outside();var history=new FrameCameraHistory();
        var first=MediumDefinitionFixtures.domain(false);var changed=MediumDefinitionFixtures.domain(true);
        history.submitted(1,0,frame,first);
        assertTrue(history.resolve(1,0,frame,first).previousValid());
        assertFalse(history.resolve(1,0,frame,changed).previousValid());
        assertTrue(history.resolve(1,0,frame,first).previousValid(),"Cancelled candidate changed submitted history");
        history.submitted(1,0,frame,changed);assertFalse(history.resolve(1,0,frame,first).previousValid());
        var scene=MediumDefinitionFixtures.scene();
        var a=new WorldLightDomain(1,scene.geometry(),List.of(),List.of(),first);
        assertTrue(a.matches(new WorldLightDomain(1,scene.geometry(),List.of(),List.of(),first)));
        assertFalse(a.matches(new WorldLightDomain(1,scene.geometry(),List.of(),List.of(),changed)));
        history.clear();assertFalse(history.resolve(1,0,frame,changed).previousValid());
    }
}
