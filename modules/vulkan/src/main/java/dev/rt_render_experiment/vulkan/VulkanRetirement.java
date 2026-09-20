package dev.rt_render_experiment.vulkan;


public final class VulkanRetirement {
   private VulkanRetirement() {
   }

   public static Throwable attempt(final Throwable previous, final Runnable action) {
      try {
         action.run();
      } catch (RuntimeException | Error failure) {
         if (previous == null) {
            return failure;
         }
         if (failure != previous) {
            previous.addSuppressed(failure);
         }
      }
      return previous;
   }

   public static void suppress(final Throwable primary, final Runnable action) {
      try {
         action.run();
      } catch (RuntimeException | Error failure) {
         if (failure != primary) {
            primary.addSuppressed(failure);
         }
      }
   }

   public static void finish(final Throwable failure) {
      if (failure instanceof RuntimeException runtime) {
         throw runtime;
      }
      if (failure instanceof Error error) {
         throw error;
      }
      if (failure != null) {
         throw new AssertionError("unexpected checked retirement failure", failure);
      }
   }
}
