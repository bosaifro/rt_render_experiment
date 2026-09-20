package dev.rt_render_experiment.engine;

import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.ProducerResources;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.SceneInputs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProducerRegistryTest {
    private static final SceneInputs.Key KEY = new SceneInputs.Key(77, 2);
    private static ProducerResources.Geometry geometry(long generation, long revision) {
        var source = SceneFixtures.prepare(false).geometry().getFirst().input();
        var primitive = source.primitives().getFirst();
        var buffer = new ResourceViews.Buffer(new ResourceViews.ResourceId(3), 1, generation * 100, generation,
            112, 0, 112, 0, 1);
        var grant = new HostExecution.BufferGrant(buffer, revision, Set.of(HostExecution.Access.TRANSFER_READ), 20);
        return new ProducerResources.Geometry(KEY, 1, generation, revision, 1, 1, grant, ProducerResources.Layout.BLOCK28,
            ProducerResources.Topology.QUADS_012_230, 4, source.origin(), source.current(), source.previous(),
            new Participation(Participation.WORLD, true),
            List.of(new ProducerResources.Primitive(primitive.part(), primitive.ordinal(), primitive.surface(), 0xffaabbcc)));
    }

    @Test void publicationIsEventDrivenAndIncludesOffCameraResidency() {
        var registry = new ProducerRegistry(1);
        var first = geometry(1, 1);
        assertTrue(registry.publish(first));
        assertFalse(registry.publish(first));
        var batch = registry.pending();
        assertEquals(List.of(first), batch.published());
        registry.acknowledge(batch);
        assertTrue(registry.pending().empty());
        assertEquals(List.of(first), registry.resident());
        assertEquals(2, registry.revision());
    }

    @Test void withdrawalsAreGenerationAwareAndCannotResurrectFromLateCallbacks() {
        var registry = new ProducerRegistry(1);
        assertTrue(registry.publish(geometry(2, 1)));
        assertFalse(registry.withdraw(new ProducerResources.Withdrawal(KEY, 1, 1, 8)));
        assertEquals(1, registry.resident().size());
        assertTrue(registry.withdraw(new ProducerResources.Withdrawal(KEY, 1, 2, 1)));
        assertFalse(registry.publish(geometry(2, 1)));
        assertFalse(registry.publish(geometry(1, 20)));
        assertTrue(registry.publish(geometry(3, 1)));
        assertEquals(1, registry.pending().published().size());
        assertTrue(registry.pending().withdrawn().isEmpty());
    }

    @Test void acknowledgingAnOlderBatchKeepsNewerUpdatesAndWithdrawals() {
        var registry = new ProducerRegistry(1);
        registry.publish(geometry(1, 1));
        var old = registry.pending();
        var second = geometry(1, 2); registry.publish(second);
        registry.acknowledge(old);
        assertEquals(List.of(second), registry.pending().published());
        registry.withdraw(new ProducerResources.Withdrawal(KEY, 1, 1, 2));
        registry.acknowledge(old);
        assertEquals(1, registry.pending().withdrawn().size());
        assertTrue(registry.resident().isEmpty());
    }

    @Test void rejectsForeignWorldAndExpiredOrUngrantedAccess() {
        var registry = new ProducerRegistry(2);
        var geometry = geometry(1, 1);
        assertThrows(IllegalArgumentException.class, () -> registry.publish(geometry));
        assertThrows(IllegalArgumentException.class, () -> registry.withdraw(new ProducerResources.Withdrawal(KEY, 1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> geometry.requireWindow(
            new HostExecution.Window(1, 1, 2, HostExecution.Stage.SCENE_PREPARATION, List.of())));
        geometry.requireWindow(new HostExecution.Window(1, 1, 2, HostExecution.Stage.SCENE_PREPARATION, List.of(geometry.vertices())));
        assertThrows(IllegalArgumentException.class, () -> new HostExecution.Window(1, 1, 21,
            HostExecution.Stage.SCENE_PREPARATION, List.of(geometry.vertices())));
    }
}
