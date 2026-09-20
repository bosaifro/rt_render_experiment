
package dev.rt_render_experiment.engine;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.SurfaceProperties;
final class MediumProfiles {
    private MediumProfiles() {}
    static MediumInputs.Kind kind(int material,int properties) {
        if((properties&SurfaceProperties.TRANSLUCENT)!=0)return null;
        var kind=switch(material) {
            case 8,9 -> MediumInputs.Kind.WATER;
            case 18,24,43,44 -> MediumInputs.Kind.GLASS;
            default -> null;
        };
        return kind==MediumInputs.Kind.GLASS && (properties&SurfaceProperties.THIN)!=0?null:kind;
    }
    static boolean potential(SceneInputs.Surface surface) {
        return surface.boundary()!=SceneInputs.Boundary.UNQUALIFIED || surface.coverage()!=SceneInputs.Coverage.FILTER && kind(surface.material(),surface.properties())!=null;
    }
    static boolean opaqueContact(SceneInputs.Surface surface) {
        if(surface.boundary()==SceneInputs.Boundary.NESTED_VOLUME || surface.coverage()!=SceneInputs.Coverage.OPAQUE || surface.layer()!=0 || surface.layerSeparation()!=0
            || (surface.properties()&(SurfaceProperties.THIN|SurfaceProperties.TRANSLUCENT))!=0)return false;
        return switch(surface.material()) { case 0,1,2,3,4,5,6,7,10,11,12,13,14,15,16,17,20,21,22,23,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,45,46,47 -> true; default -> false; };
    }
    static boolean sheet(int material) { return material==48 || material==49; }
    static MediumInputs.Definition definition(MediumInputs.Kind kind,SceneInputs.Vec3 color) {
        return switch(kind) {
            case WATER -> new MediumInputs.Definition(kind,1.333f,new SceneInputs.Vec3(0.35f,0.1f,0.06f),new SceneInputs.Vec3(0.004f,0.007f,0.012f));
            case GLASS -> new MediumInputs.Definition(kind,1.5f,new SceneInputs.Vec3(absorption(0.05f,color.x()),absorption(0.05f,color.y()),absorption(0.05f,color.z())),new SceneInputs.Vec3(0.0f,0.0f,0.0f));
            case ICE -> new MediumInputs.Definition(kind,1.31f,new SceneInputs.Vec3(absorption(0.0f,color.x()),absorption(0.0f,color.y()),absorption(0.0f,color.z())),new SceneInputs.Vec3(0.01f,0.015f,0.025f));
        };
    }
    private static float absorption(float base,float color) {
        return base-(float)Math.log(Math.clamp(color,0.04f,0.98f))*0.7f;
    }
}
