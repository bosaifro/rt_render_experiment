package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.DynamicInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DynamicCompilerTest {
    @Test void revisionsAndPublication() {
        var compiler=new DynamicSceneCompiler();var source=input(0,1);
        var first=compiler.prepare(1,1,List.of(source),4096);var a=first.geometry().getFirst();first.submitted();
        var same=compiler.prepare(1,1,List.of(input(2,1)),4096);var b=same.geometry().getFirst();
        assertEquals(a.positionHash(),b.positionHash(),"Camera motion must not deform prepared world content");
        assertEquals(a.input().revision(),b.input().revision());
        var topology=compiler.prepare(1,1,List.of(input(2,2)),4096);
        assertTrue(topology.geometry().getFirst().input().revision().topology()>a.input().revision().topology());
        assertFalse(topology.geometry().getFirst().input().previousValid());
        same.submitted();assertThrows(IllegalStateException.class,topology::submitted,"Out-of-order CPU publication must not replace its predecessor");
        assertThrows(IllegalStateException.class,()->compiler.prepare(1,1,List.of(source),1));
        assertEquals(b.input().revision(),compiler.prepare(1,1,List.of(source),4096).geometry().getFirst().input().revision());
        compiler.clear();assertThrows(IllegalStateException.class,topology::submitted);
    }
    private static DynamicInputs.PreparedObject input(float cameraDelta,long topology) {
        var base=SceneFixtures.prepare(false).geometry().getFirst().input();
        var p=base.primitives().getFirst();var corners=p.corners().stream().map(c->new SceneInputs.Corner(
            new SceneInputs.Vec3(c.position().x()-cameraDelta,c.position().y(),c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent(),c.emissionUv(),c.emissionTint(),c.overlayTexel())).toList();
        return new DynamicInputs.PreparedObject(base.key(),new SceneInputs.Origin(30_000_000+cameraDelta,64,0),SceneInputs.Transform.identity(),SceneInputs.Motion.DEFORMING,
            base.participation(),topology,1,List.of(new SceneInputs.Primitive(p.part(),p.ordinal(),p.surface(),corners)));
    }
    @Test void shadingDeformationIsNotAppearance() {
        var compiler=new DynamicSceneCompiler();var input=input(0,1);var first=compiler.prepare(1,1,List.of(input),4096);first.submitted();
        var primitive=input.primitives().getFirst();
        var corners=primitive.corners().stream().map(c->new SceneInputs.Corner(c.position(),c.u(),c.v(),c.tint(),new SceneInputs.Vec3(0,0.6f,0.8f),c.tangent(),c.emissionUv(),c.emissionTint(),c.overlayTexel())).toList();
        var changed=new DynamicInputs.PreparedObject(input.key(),input.origin(),input.transform(),input.motion(),input.participation(),1,1,
            List.of(new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),primitive.surface(),corners)));
        var next=compiler.prepare(1,1,List.of(changed),4096).geometry().getFirst();var before=first.geometry().getFirst();
        assertEquals(before.positionHash(),next.positionHash());assertEquals(before.appearanceHash(),next.appearanceHash());
        assertNotEquals(before.deformationHash(),next.deformationHash());
        assertEquals(before.input().revision().appearance(),next.input().revision().appearance());
        assertTrue(next.input().revision().deformation()>before.input().revision().deformation());
    }
}
