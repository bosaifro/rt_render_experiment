package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorldLightDomainTest {
    @Test void exactWorldFactsAndIndependentDynamicStratum() {
        var geometry=SceneFixtures.prepare(false).geometry();var base=geometry.getFirst().input();
        var world=point(1,1,LightInputs.Stratum.WORLD,0);var held=point(2,1,LightInputs.Stratum.CAMERA_ATTACHED,0);
        var a=new WorldLightDomain(1,geometry,List.of(world,held),List.of());
        assertTrue(a.matches(new WorldLightDomain(1,SceneFixtures.prepare(false).geometry(),List.of(point(2,2,LightInputs.Stratum.CAMERA_ATTACHED,2),world),List.of())));
        assertFalse(a.matches(new WorldLightDomain(2,geometry,List.of(world,held),List.of())));
        assertFalse(a.matches(new WorldLightDomain(1,geometry,List.of(point(1,2,LightInputs.Stratum.WORLD,0),held),List.of())));
        assertFalse(a.matches(new WorldLightDomain(1,geometry,List.of(point(1,1,LightInputs.Stratum.WORLD,16),held),List.of())));
        assertFalse(a.matches(new WorldLightDomain(1,geometry,List.of(held),List.of())));
        var changed=base.current().rows();changed[3]=.5f;
        var moved=new SceneCompiler().compile(new SceneInputs.Geometry(base.key(),base.revision(),base.origin(),new SceneInputs.Transform(changed),base.previous(),false,
            base.motion(),base.participation(),base.primitives()));
        assertFalse(a.matches(new WorldLightDomain(1,List.of(moved),List.of(world,held),List.of())));
        var dynamic=new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(99,1),base.revision(),base.origin(),new SceneInputs.Transform(changed),base.previous(),false,
            SceneInputs.Motion.RIGID,base.participation(),base.primitives()));
        assertTrue(a.matches(new WorldLightDomain(1,List.of(dynamic,geometry.getFirst()),List.of(world,held),List.of())));
        var primitive=base.primitives().getFirst();
        var area=new LightInputs.Area(new SceneInputs.Key(4,1),1,world.emission(),world.position(),0,LightInputs.Stratum.WORLD,dynamic.input().key(),primitive.part(),primitive.ordinal());
        assertFalse(a.matches(new WorldLightDomain(1,List.of(dynamic,geometry.getFirst()),List.of(world,held,area),List.of())));
    }
    @Test void frameCopiesKeepIndependentReusePolicy() {
        var base=SceneFixtures.frame();assertFalse(base.worldLightReuse());
        var enabled=base.withWorldLightReuse(true);
        assertTrue(enabled.withEnvironment(base.environment()).withProjectionJitter(true).withPresentation(base.presentation())
            .withOutputExtent(32,32).withReconstruction(RenderFrame.Reconstruction.PORTABLE).worldLightReuse());
        assertTrue(enabled.withView(RenderFrame.View.VIEWMODEL).worldLightReuse());
        assertTrue(enabled.withSubmittedPredecessor(base,false).worldLightReuse());
        assertFalse(enabled.compatibleHistory(base));
    }
    @Test void areaDependenciesFollowEmissionResourcesAndReferencedGeometry() {
        var textureKey=new SceneInputs.Key(930,1);
        var prepared=SceneFixtures.prepare(false,SceneInputs.Coverage.OPAQUE,textureKey).geometry().getFirst();
        var source=prepared.input();var quad=source.primitives().getFirst();
        var mesh=new SceneCompiler().compile(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),false,
            SceneInputs.Motion.RIGID,source.participation(),source.primitives()));
        var area=new LightInputs.Area(new SceneInputs.Key(931,1),1,LightInputs.Emission.areaImportance(new SceneInputs.Vec3(1,1,1)),source.origin(),0,
            LightInputs.Stratum.WORLD,source.key(),quad.part(),quad.ordinal());
        var color=texture(textureKey,1);var irrelevant=texture(new SceneInputs.Key(932,1),1);
        var base=new WorldLightDomain(1,List.of(mesh),List.of(area),List.of(color,irrelevant));
        assertTrue(base.matches(new WorldLightDomain(1,List.of(mesh),List.of(area),List.of(texture(irrelevant.key(),2),color))));
        assertFalse(base.matches(new WorldLightDomain(1,List.of(mesh),List.of(area),List.of(texture(textureKey,2),irrelevant))));
        var transform=source.current().rows();transform[3]=.1f;
        var moved=new SceneCompiler().compile(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),new SceneInputs.Transform(transform),source.previous(),false,
            SceneInputs.Motion.RIGID,source.participation(),source.primitives()));
        assertFalse(base.matches(new WorldLightDomain(1,List.of(moved),List.of(area),List.of(color,irrelevant))),"A WORLD area cannot hide its emitter motion in the dynamic stratum");
        var point=point(1,1,LightInputs.Stratum.WORLD,0);
        var pointDomain=new WorldLightDomain(1,List.of(prepared),List.of(point),List.of(color));
        assertTrue(pointDomain.matches(new WorldLightDomain(1,List.of(prepared),List.of(point),List.of(texture(textureKey,2)))),
            "Receiver-only texture invalidation belongs to its surface token, not global source support");
    }
    private static TextureInputs.Texture texture(SceneInputs.Key key,long revision) {
        return new TextureInputs.Texture(key,revision,TextureInputs.Encoding.SRGB,TextureInputs.Address.CLAMP,
            List.of(new TextureInputs.Level(1,1,java.nio.ByteBuffer.wrap(new byte[]{-1,-1,-1,-1}))));
    }
    private static LightInputs.Point point(long id,long revision,LightInputs.Stratum stratum,double x) {
        var p=new SceneInputs.Origin(x,64,-1);
        return new LightInputs.Point(new SceneInputs.Key(20,id),revision,new LightInputs.Emission(0,1,new SceneInputs.Vec3(1,1,1)),p,0,stratum,p,.12f);
    }
}
