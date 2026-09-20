package dev.rt_render_experiment.reentry;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
public final class HostCamera {
    private static Matrix4f projection;
    private HostCamera() {}
    public static void publish(Matrix4fc matrix) { projection=new Matrix4f(matrix); }
    public static Matrix4fc projection() { return java.util.Objects.requireNonNull(projection,"Host has not published its world projection"); }
}
