
package dev.rt_render_experiment.contract;
public final class SurfaceProperties {
    private SurfaceProperties() {}
    public static final int WET = 1;
    public static final int THIN = 2;
    public static final int GLAZED = 4;
    public static final int HURT_SHIFT = 8;
    public static final int FLASH_SHIFT = 12;
    public static final int LIGHT_SHIFT = 16;
    public static final int TRANSLUCENT = 1048576;
}
