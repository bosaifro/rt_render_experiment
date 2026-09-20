package dev.rt_render_experiment.contract;

import java.util.List;
import java.util.Objects;
import java.util.Set;


public final class HostExecution {
    public static final int API_VERSION = 3;
    private HostExecution() {}

    public record DepthSources(Set<SceneInputs.Key> geometry) {
        public DepthSources { geometry=Set.copyOf(geometry); }
        public static DepthSources none() { return new DepthSources(Set.of()); }
    }
    public enum Capability { BUFFER_ADDRESS, ACCELERATION_STRUCTURE, RAY_PIPELINE, RAY_QUERY, OPACITY_MICROMAP, INVOCATION_RT_RENDER_EXPERIMENTDER, RECONSTRUCTION_RR, SAMPLER_ANISOTROPY }
    public enum Stage { SCENE_PREPARATION, WORLD, BEFORE_TRANSPARENT, AFTER_TRANSPARENT, VIEWMODEL, DISPLAY }
    public enum Access { TRANSFER_READ, TRANSFER_WRITE, BUILD_READ, BUILD_WRITE, SHADER_READ, SHADER_WRITE, ATTACHMENT_READ, ATTACHMENT_WRITE }
    public enum State { RESERVED, RECORDED, ACCEPTED, SUBMITTED, COMPLETED, RETIRED, CANCELLED }
    public record Capabilities(long device, Set<Capability> enabled, long scratchAlignment, long maximumInstances) {
        public Capabilities {
            enabled = Set.copyOf(enabled);
            if (device <= 0 || scratchAlignment <= 0 || (scratchAlignment & (scratchAlignment-1)) != 0
                || maximumInstances <= 0) throw new IllegalArgumentException("Invalid device capabilities");
        }
        public void require(Capability capability) {
            if (!enabled.contains(capability)) throw new IllegalStateException("Required renderer capability unavailable: " + capability);
        }
    }

    public record BufferGrant(ResourceViews.Buffer view, long contentRevision, Set<Access> allowed, long validThroughRecording) {
        public BufferGrant {
            Objects.requireNonNull(view); allowed = Set.copyOf(allowed);
            if (contentRevision <= 0 || allowed.isEmpty() || validThroughRecording <= 0) throw new IllegalArgumentException("Invalid buffer grant");
        }
    }
    public record ImageGrant(ResourceViews.Image view,long contentRevision,Set<Access> allowed,long validThroughRecording) {
        public ImageGrant {
            Objects.requireNonNull(view);allowed=Set.copyOf(allowed);
            if(contentRevision<=0 || allowed.isEmpty() || validThroughRecording<=0)throw new IllegalArgumentException("Invalid image grant");
        }
    }
    public record Window(long device, long transaction, long recording, Stage stage, List<BufferGrant> buffers,List<ImageGrant> images) {
        public Window(long device,long transaction,long recording,Stage stage,List<BufferGrant> buffers) { this(device,transaction,recording,stage,buffers,List.of()); }
        public Window {
            Objects.requireNonNull(stage); buffers = List.copyOf(buffers);images=List.copyOf(images);
            if (device <= 0 || transaction <= 0 || recording <= 0) throw new IllegalArgumentException("Invalid recording window");
            for (BufferGrant grant : buffers) if (grant.view().device() != device || grant.validThroughRecording() < recording)
                throw new IllegalArgumentException("Expired or foreign window resource");
            for(ImageGrant grant:images)if(grant.view().device()!=device || grant.validThroughRecording()<recording)
                throw new IllegalArgumentException("Expired or foreign image grant");
        }
    }

    public static final class Transaction {
        private final long id;
        private State state = State.RESERVED;
        private long submission;
        public Transaction(long id) { if (id <= 0) throw new IllegalArgumentException("Invalid transaction"); this.id = id; }
        public long id() { return id; }
        public State state() { return state; }
        public long submission() { return submission; }
        public void recorded() { transition(State.RESERVED, State.RECORDED); }
        public void accepted() { transition(State.RECORDED, State.ACCEPTED); }
        public void submitted(long value) {
            if (value <= 0) throw new IllegalArgumentException("Invalid submission");
            transition(State.ACCEPTED, State.SUBMITTED); submission = value;
        }
        public void completed(long observed) {
            if (observed < submission) throw new IllegalStateException("Submission not completed");
            transition(State.SUBMITTED, State.COMPLETED);
        }
        public void retire() { transition(State.COMPLETED, State.RETIRED); }
        public void cancel() {
            if (state != State.RESERVED && state != State.RECORDED && state != State.ACCEPTED)
                throw new IllegalStateException("Cannot cancel submitted or terminal work");
            state = State.CANCELLED;
        }
        private void transition(State expected, State next) {
            if (state != expected) throw new IllegalStateException("Illegal transaction transition " + state + " -> " + next);
            state = next;
        }
    }
}
