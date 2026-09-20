package dev.rt_render_experiment.vulkan;

import dev.rt_render_experiment.contract.ResourceViews.Submission;


public final class SubmissionLifetime {
   private final long device;
   private long recording;
   private long submitted;
   private long completed;

   public SubmissionLifetime(final Submission initial) {
      this.device = java.util.Objects.requireNonNull(initial, "initial").device();
      observe(initial);
   }

   public void observe(final Submission observation) {
      java.util.Objects.requireNonNull(observation, "observation");
      if (observation.device() != this.device || observation.recording() < this.recording
         || observation.submitted() < this.submitted || observation.completed() < this.completed) {
         throw new IllegalArgumentException("Stale or foreign Vulkan submission observation");
      }
      this.recording = observation.recording();
      this.submitted = observation.submitted();
      this.completed = observation.completed();
   }

   public long recording() { return this.recording; }
   public long device() { return this.device; }
   public long submitted() { return this.submitted; }
   public long completed() { return this.completed; }

   public boolean reusable(final long lastUse) {
      if (lastUse < 0L || lastUse > this.recording) throw new IllegalArgumentException("Invalid resource use serial");
      return lastUse <= this.completed;
   }
}
