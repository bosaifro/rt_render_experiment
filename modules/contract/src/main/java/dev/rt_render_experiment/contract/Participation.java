
package dev.rt_render_experiment.contract;
public record Participation(int rayMask, boolean primaryVisible) {
    public static final int CAMERA_PATH = 1;
    public static final int REFLECTION = 2;
    public static final int SHADOW = 4;
    public static final int TRANSPORT = 8;
    public static final int VIEWMODEL = 16;
    public static final int RASTER_ONLY = 128;
    public static final int WORLD = CAMERA_PATH | REFLECTION | SHADOW | TRANSPORT;
    public Participation {
        if (rayMask <= 0 || rayMask > 255 || (rayMask & VIEWMODEL) != 0 && rayMask != VIEWMODEL)
            throw new IllegalArgumentException("Invalid or mixed view/world ray participation");
    }
}
