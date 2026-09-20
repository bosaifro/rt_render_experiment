package dev.rt_render_experiment.runtime;


public final class MaterialManifestException extends IllegalStateException {
   private static final long serialVersionUID = 1L;

   public MaterialManifestException(final String message) {
      super(message);
   }

   public MaterialManifestException(final String message, final Throwable cause) {
      super(message, cause);
   }
}
