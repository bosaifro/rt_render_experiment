package dev.rt_render_experiment.engine;


final class FrameCameraHistory {
    private RenderFrame previous;
    private dev.rt_render_experiment.contract.MediumInputs.Domain previousMedia;
    private long world,continuity;

    RenderFrame resolve(long world,long continuity,RenderFrame input) {
        return resolve(world,continuity,input,null);
    }
    RenderFrame resolve(long world,long continuity,RenderFrame input,dev.rt_render_experiment.contract.MediumInputs.Domain media) {
        if(world<=0 || world<this.world || continuity<0)throw new IllegalArgumentException("Invalid camera history epoch");
        boolean valid=previous!=null && input.previousValid() && world==this.world && continuity==this.continuity
            && java.util.Objects.equals(media,previousMedia) && input.compatibleHistory(previous) && continuousPosition(input,previous);
        return input.withSubmittedPredecessor(previous,valid);
    }
    void submitted(long world,long continuity,RenderFrame frame) {
        submitted(world,continuity,frame,null);
    }
    void submitted(long world,long continuity,RenderFrame frame,dev.rt_render_experiment.contract.MediumInputs.Domain media) {
        previous=frame;this.world=world;this.continuity=continuity;
        previousMedia=media;
    }
    void clear() { previous=null;previousMedia=null; }
    private static boolean continuousPosition(RenderFrame a,RenderFrame b) {
        double x=a.eye().x()-b.eye().x(),y=a.eye().y()-b.eye().y(),z=a.eye().z()-b.eye().z();
        double limit=dev.rt_render_experiment.vulkan.FrameUniformModel.MAX_HISTORY_CAMERA_DELTA_BLOCKS;
        return x*x+y*y+z*z<=limit*limit;
    }
}
