package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.engine.abi.R2Abi;
import dev.rt_render_experiment.vulkan.VulkanResources;
import dev.rt_render_experiment.vulkan.CompletionPool;
import org.lwjgl.vulkan.VK12;


final class LightHistoryStorage implements AutoCloseable {
    private record History(VulkanResources.Buffer buffer,int width,int height,WorldLightDomain domain,CompletionPool<R2Scratch>.Lease storage) {
        void close() { storage.releaseAfter(buffer.lastUse()); }
    }
    private final VulkanResources resources;
    private final VulkanResources.Buffer empty;
    private History previous;
    private final CompletionPool<R2Scratch> histories;
    LightHistoryStorage(VulkanResources resources) {
        this.resources=resources;
        histories=new CompletionPool<>(4,()->new R2Scratch(resources),R2Scratch::close);
        empty=resources.allocateMapped(R2Abi.LightHistoryRecord.SIZE,VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        try(var mapping=empty.map(0,empty.view().length())) { var data=mapping.data();while(data.hasRemaining())data.put((byte)0); }
        catch(RuntimeException|Error failure) { dev.rt_render_experiment.vulkan.VulkanRetirement.suppress(failure,empty::close);throw failure; }
    }
    Prepared prepare(RenderFrame frame,WorldLightDomain domain) { return new Prepared(frame,domain); }
    final class Prepared implements AutoCloseable {
        private final long recording=resources.recording();
        private final History current;
        private final VulkanResources.Buffer before;
        private final boolean world,valid;
        private boolean committed,closed;
        private Prepared(RenderFrame frame,WorldLightDomain domain) {
            world=frame.view()==RenderFrame.View.WORLD;
            boolean enabled=world && frame.worldLightReuse();
            valid=enabled && frame.previousValid() && previous!=null && previous.width==frame.width() && previous.height==frame.height() && domain.matches(previous.domain);
            before=valid?previous.buffer:empty;
            if(enabled) {
                var lease=histories.acquire(resources.recording(),resources.completed());
                try { current=new History(lease.value().buffer(0,Math.multiplyExact(Math.multiplyExact(frame.width(),frame.height()),R2Abi.LightHistoryRecord.SIZE),
                    VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,false),frame.width(),frame.height(),domain,lease); }
                catch(RuntimeException|Error failure) { lease.close();throw failure; }
            } else current=null;
        }
        boolean enabled() { return current!=null; }
        boolean valid() { return valid; }
        VulkanResources.Buffer input() { return before; }
        VulkanResources.Buffer output() { return current==null?empty:current.buffer; }
        void markUsed() { before.markUsed();output().markUsed(); }
        void submitted(long serial) {
            if(closed || committed || serial!=recording)throw new IllegalStateException("Invalid world-light history submission");
            committed=true;
            if(world) {
                var retired=previous;previous=current;
                if(retired!=null)retired.close();
            }
        }
        @Override public void close() { if(!closed) { closed=true;if(!committed && current!=null)current.close(); } }
    }
    @Override public void close() { if(previous!=null) { previous.close();previous=null; }histories.close();empty.close(); }
}
