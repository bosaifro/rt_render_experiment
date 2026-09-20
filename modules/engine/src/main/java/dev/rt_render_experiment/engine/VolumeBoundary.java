package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.SceneInputs;


sealed interface VolumeBoundary permits ConvexVolume,TriangleVolume {
    double clearance(SceneInputs.Origin eye);
    double uncertainty(SceneInputs.Origin eye,SceneInputs.Origin frameOrigin);
    long logicalBytes();
    List<ConvexVolume.Point> vertices();
    double condition();


    default boolean strictlyContains(VolumeBoundary other,ConvexVolume.Budget budget) { return false; }
    default boolean separatedBounds(VolumeBoundary other,ConvexVolume.Budget budget) {
        for(int axis=0;axis<3;axis++) {
            budget.spend(vertices().size()+other.vertices().size());final int coordinate=axis;
            double lo=vertices().stream().mapToDouble(p->p.axis(coordinate)).min().orElseThrow(),hi=vertices().stream().mapToDouble(p->p.axis(coordinate)).max().orElseThrow();
            double a=other.vertices().stream().mapToDouble(p->p.axis(coordinate)).min().orElseThrow(),b=other.vertices().stream().mapToDouble(p->p.axis(coordinate)).max().orElseThrow();
            double tolerance=32*Math.max(condition(),other.condition())*Math.ulp(Math.max(Math.max(Math.abs(lo),Math.abs(hi)),Math.max(Math.abs(a),Math.abs(b))));
            if(hi+tolerance<a || b+tolerance<lo)return true;
        }
        return false;
    }
}
