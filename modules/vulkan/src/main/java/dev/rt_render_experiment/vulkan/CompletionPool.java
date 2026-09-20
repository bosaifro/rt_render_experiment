package dev.rt_render_experiment.vulkan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;


public final class CompletionPool<T> implements AutoCloseable {
    private final int capacity;
    private final Supplier<T> create;
    private final Consumer<T> destroy;
    private final List<Slot> slots = new ArrayList<>();
    private long recording, completed;
    private boolean closed;

    public CompletionPool(int capacity, Supplier<T> create, Consumer<T> destroy) {
        if (capacity < 1) throw new IllegalArgumentException("Empty completion pool");
        this.capacity = capacity;
        this.create = Objects.requireNonNull(create);
        this.destroy = Objects.requireNonNull(destroy);
    }


    public Lease acquire(long recording, long completed) {
        if (closed) throw new IllegalStateException("Closed completion pool");
        if (recording <= 0 || completed < 0 || completed >= recording
            || recording < this.recording || completed < this.completed)
            throw new IllegalArgumentException("Invalid or stale completion observation");
        this.recording = recording;
        this.completed = completed;
        for (Slot slot : slots) {
            if (!slot.leased && slot.lastUse <= completed) return new Lease(slot, recording);
        }
        if (slots.size() == capacity) throw new IllegalStateException("No completed scratch slot available");
        Slot slot = new Slot(Objects.requireNonNull(create.get()));
        slots.add(slot);
        return new Lease(slot, recording);
    }

    public int size() { return slots.size(); }

    private final class Slot {
        private final T value;
        private long lastUse;
        private boolean leased;
        private Slot(T value) { this.value = value; }
    }

    public final class Lease implements AutoCloseable {
        private final Slot slot;
        private boolean released;
        private Lease(Slot slot, long recording) {
            this.slot = slot;
            slot.leased = true;

            slot.lastUse = recording;
        }
        public T value() {
            if (released || closed) throw new IllegalStateException("Expired scratch lease");
            return slot.value;
        }
        @Override public void close() {
            releaseAfter(slot.lastUse);
        }

        public void releaseAfter(long lastUse) {
            if(lastUse<0)throw new IllegalArgumentException("Negative history use serial");
            if (!released) {
                slot.lastUse=Math.max(slot.lastUse,lastUse);
                recording=Math.max(recording,slot.lastUse);
                released = true; slot.leased = false;
            }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (Slot slot : slots) failure = VulkanRetirement.attempt(failure, () -> destroy.accept(slot.value));
        slots.clear();
        VulkanRetirement.finish(failure);
    }
}
