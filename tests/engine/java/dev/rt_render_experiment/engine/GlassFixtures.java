package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.SceneInputs;

final class GlassFixtures {
    static List<MediumInputs.Enclosure> glass() {
        return List.of(new MediumInputs.Enclosure(0x100000001L,glass(.8f,.8f,.8f)),new MediumInputs.Enclosure(0x200000001L,glass(.3f,.6f,.9f)));
    }
    private static MediumInputs.Definition glass(float r,float g,float b) {
        return new MediumInputs.Definition(MediumInputs.Kind.GLASS,1.5f,new SceneInputs.Vec3(absorption(r),absorption(g),absorption(b)),new SceneInputs.Vec3(0,0,0));
    }
    private static float absorption(float value) { return .05f-.7f*(float)Math.log(value); }
    static MediumInputs.Origin origin(RenderFrame frame,long serial) { return new MediumInputs.Origin(1,serial,frame.eye(),.4f,glass()); }}
