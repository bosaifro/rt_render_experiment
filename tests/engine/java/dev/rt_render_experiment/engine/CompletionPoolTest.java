package dev.rt_render_experiment.engine;

import java.util.concurrent.atomic.AtomicInteger;
import dev.rt_render_experiment.vulkan.CompletionPool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CompletionPoolTest {
    @Test void historyReuseWaitsForItsLastReader() {
        try(var pool=new CompletionPool<>(1,Object::new,ignored->{})) {
            var history=pool.acquire(1,0);var identity=history.value();history.releaseAfter(3);
            assertThrows(IllegalStateException.class,()->pool.acquire(4,2));
            try(var reused=pool.acquire(4,3)) { assertSame(identity,reused.value()); }
        }
    }
    @Test void requiresBothLeaseReleaseAndObservedCompletion() {
        var created = new AtomicInteger();
        var destroyed = new AtomicInteger();
        try (var pool = new CompletionPool<>(2, created::incrementAndGet, ignored -> destroyed.incrementAndGet())) {
            var first = pool.acquire(1, 0);
            int identity = first.value();
            first.close();
            var second = pool.acquire(2, 0);
            assertNotEquals(identity, second.value());
            assertThrows(IllegalStateException.class, () -> pool.acquire(2, 0));
            var reused = pool.acquire(3, 1);
            assertEquals(identity, reused.value());
            assertThrows(IllegalStateException.class, () -> pool.acquire(4, 3));
            second.close(); reused.close();
            try (var available = pool.acquire(4, 3)) { assertNotNull(available.value()); }
            assertEquals(2, pool.size());
        }
        assertEquals(2, destroyed.get());
    }

    @Test void cancellationDoesNotMeanCompletionAndStaleObservationsAreRejected() {
        try (var pool = new CompletionPool<>(1, Object::new, ignored -> {})) {
            var lease = pool.acquire(5, 4); lease.close();
            assertThrows(IllegalStateException.class, lease::value);
            assertThrows(IllegalStateException.class, () -> pool.acquire(6, 4));
            assertThrows(IllegalArgumentException.class, () -> pool.acquire(5, 4));
            assertThrows(IllegalArgumentException.class, () -> pool.acquire(6, 3));
            assertThrows(IllegalArgumentException.class, () -> pool.acquire(6, 6));
            try (var next = pool.acquire(7, 6)) { assertNotNull(next.value()); }
        }
    }

    @Test void failedCreationDoesNotConsumeCapacityAndCloseInvalidatesLeases() {
        var tries = new AtomicInteger();
        var destroyed = new AtomicInteger();
        var pool = new CompletionPool<>(1, () -> {
            if (tries.incrementAndGet() == 1) throw new IllegalStateException("allocation failed");
            return new Object();
        }, ignored -> destroyed.incrementAndGet());
        assertThrows(IllegalStateException.class, () -> pool.acquire(1, 0));
        var lease = pool.acquire(1, 0);
        pool.close(); pool.close(); lease.close();
        assertThrows(IllegalStateException.class, lease::value);
        assertThrows(IllegalStateException.class, () -> pool.acquire(2, 1));
        assertEquals(1, destroyed.get());
    }
}
