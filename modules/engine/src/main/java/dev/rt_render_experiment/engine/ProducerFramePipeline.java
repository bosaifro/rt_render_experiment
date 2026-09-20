package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.*;
import dev.rt_render_experiment.vulkan.VulkanResources;
import dev.rt_render_experiment.vulkan.VulkanRetirement;
import org.lwjgl.vulkan.VkCommandBuffer;


public final class ProducerFramePipeline implements AutoCloseable {
    private final ProducerGpuScene scene;
    private final R2Renderer renderer;
    private final FrameCameraHistory cameras=new FrameCameraHistory();
    public ProducerFramePipeline(VulkanResources resources,HostExecution.Capabilities capabilities,R2ShaderPackage shaders) {
        scene=new ProducerGpuScene(resources,capabilities,shaders);
        try { renderer=new R2Renderer(resources,capabilities,shaders); }
        catch(RuntimeException|Error failure) { VulkanRetirement.suppress(failure,scene::close);throw failure; }
    }
    public Frame record(HostExecution.Window preparation,VkCommandBuffer command,ProducerRegistry registry,
                         List<? extends TextureInputs.Resource> textures,LightInputs.Publication lights,MediumInputs.Domain media,
                         RenderFrame input,long continuity) {
        return record(preparation,command,registry,textures,lights,media,input,continuity,null);
    }
    public Frame record(HostExecution.Window preparation,VkCommandBuffer command,ProducerRegistry registry,
                         List<? extends TextureInputs.Resource> textures,LightInputs.Publication lights,MediumInputs.Domain media,
                         RenderFrame input,long continuity,java.util.function.BiConsumer<VkCommandBuffer,Integer> timings) {
        var frame=cameras.resolve(registry.world(),continuity,input,media);
        var prepared=scene.prepare(preparation,registry,frame.origin(),textures,lights,media);
        try {
            prepared.record(command);
            if(timings!=null)timings.accept(command,1);
            var output=renderer.record(new HostExecution.Window(preparation.device(),preparation.transaction(),preparation.recording(),HostExecution.Stage.WORLD,List.of()),
                command,prepared.scene(),frame,false);
            if(timings!=null)timings.accept(command,2);
            return new Frame(preparation,registry.world(),continuity,frame,media,prepared,output);
        } catch(RuntimeException|Error failure) { VulkanRetirement.suppress(failure,prepared::close);throw failure; }
    }
    public final class Frame implements AutoCloseable {
        private final HostExecution.Window preparation;
        private final long world,continuity;
        private final RenderFrame frame;
        private final MediumInputs.Domain media;
        private final ProducerGpuScene.Prepared scene;
        private final R2Renderer.Output output;
        private boolean published,displayed,submitted,closed;
        private Frame(HostExecution.Window preparation,long world,long continuity,RenderFrame frame,MediumInputs.Domain media,
                      ProducerGpuScene.Prepared scene,R2Renderer.Output output) {
            this.preparation=preparation;this.world=world;this.continuity=continuity;this.frame=frame;this.media=media;this.scene=scene;this.output=output;
        }
        public void publishWorld(VkCommandBuffer command,HostExecution.ImageGrant color,HostExecution.ImageGrant depth) {
            requireOpen();if(published)throw new IllegalStateException("Repeated producer world handoff");
            renderer.publishWorld(window(HostExecution.Stage.BEFORE_TRANSPARENT,color,depth),command,output,color,depth);published=true;
        }
        public void display(VkCommandBuffer command,HostExecution.ImageGrant target) {
            requireOpen();if(!published || displayed)throw new IllegalStateException("Invalid producer display order");
            var window=window(HostExecution.Stage.DISPLAY,target);
            renderer.recordPresentation(window,command,output);renderer.publishDisplay(window,command,output,target);
            scene.scene().finishTextureReads(command);displayed=true;
        }
        public boolean represents(ProducerResources.Geometry geometry) {
            requireOpen();
            return published && geometry.participation().primaryVisible() && scene.scene().represents(geometry)
                && geometry.metadata().stream().noneMatch(p->p.surface().coverage()==SceneInputs.Coverage.FILTER);
        }
        private HostExecution.Window window(HostExecution.Stage stage,HostExecution.ImageGrant... images) {
            return new HostExecution.Window(preparation.device(),preparation.transaction(),preparation.recording(),stage,List.of(),List.of(images));
        }
        public int builtMeshes() { return scene.builtMeshes(); }
        public long copiedSourceBytes() { return scene.copiedSourceBytes(); }
        public long scratchBytes() { return output.logicalBytes(); }
        public void submitted(long serial) {
            requireOpen();if(!displayed || submitted || serial!=preparation.recording())throw new IllegalStateException("Incomplete or duplicate producer frame submission");
            scene.submitted(serial);output.submitted(serial);cameras.submitted(world,continuity,frame,media);submitted=true;
        }
        private void requireOpen() { if(closed)throw new IllegalStateException("Retired producer frame"); }
        @Override public void close() {
            if(closed)return;closed=true;Throwable failure=VulkanRetirement.attempt(null,output::close);
            failure=VulkanRetirement.attempt(failure,scene::close);VulkanRetirement.finish(failure);
        }
    }
    @Override public void close() {
        Throwable failure=VulkanRetirement.attempt(null,renderer::close);failure=VulkanRetirement.attempt(failure,scene::close);
        cameras.clear();VulkanRetirement.finish(failure);
    }
}
