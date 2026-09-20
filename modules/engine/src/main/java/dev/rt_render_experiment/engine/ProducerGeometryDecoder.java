package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.ProducerResources;
import dev.rt_render_experiment.contract.ResourceViews;
import dev.rt_render_experiment.contract.TriangleGeometry;
import dev.rt_render_experiment.engine.abi.R2Abi;
import dev.rt_render_experiment.vulkan.VulkanBarriers;
import dev.rt_render_experiment.vulkan.VulkanDescriptors;
import dev.rt_render_experiment.vulkan.VulkanResources;
import dev.rt_render_experiment.vulkan.VulkanRetirement;
import dev.rt_render_experiment.vulkan.VulkanUploads;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;


public final class ProducerGeometryDecoder implements AutoCloseable {
    private static final int STORAGE = VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
        | VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    private static final int BUILD = STORAGE | KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
    private final VulkanResources resources;
    private final VulkanUploads uploads;
    private final ComputeProgram program;
    private final long storageAlignment;
    private boolean closed;

    public ProducerGeometryDecoder(VulkanResources resources, R2ShaderPackage shaders) {
        if (!shaders.hasProducerIngress()) throw new IllegalArgumentException("Shader package has no qualified producer decoder");
        this.resources = resources;
        uploads = new VulkanUploads(resources);
        int stage = VK12.VK_SHADER_STAGE_COMPUTE_BIT;
        program = new ComputeProgram(resources, shaders, R2Abi.ProducerDecode.ENTRIES, List.of(
            VulkanDescriptors.uniform("Decode", stage), VulkanDescriptors.storageBuffer("Source", stage),
            VulkanDescriptors.storageBuffer("Tint", stage), VulkanDescriptors.storageBuffer("Facts", stage),
            VulkanDescriptors.storageBuffer("Surfaces", stage), VulkanDescriptors.storageBuffer("Positions", stage),
            VulkanDescriptors.storageBuffer("Corners", stage), VulkanDescriptors.storageBuffer("Indices", stage),
            VulkanDescriptors.storageBuffer("Triangles", stage)));
        try (var stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(resources.device().getPhysicalDevice(), properties);
            storageAlignment = properties.limits().minStorageBufferOffsetAlignment();
        }
    }


    public Decoded prepare(HostExecution.Window window, ProducerResources.Geometry geometry, ByteBuffer surfaceRecords) {
        if (closed || window.recording() != resources.recording() || window.device() != resources.deviceIdentity()
            || window.stage() != HostExecution.Stage.SCENE_PREPARATION)
            throw new IllegalArgumentException("Invalid producer decoding window");
        geometry.requireWindow(window);
        if (surfaceRecords.remaining() != Math.multiplyExact(geometry.metadata().size(), R2Abi.SurfaceRecord.SIZE))
            throw new IllegalArgumentException("Surface metadata does not cover the producer primitives");
        var source = geometry.vertices().view();
        boolean direct = geometry.vertices().allowed().contains(HostExecution.Access.SHADER_READ)
            && (source.usage() & VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) != 0 && source.offset() % storageAlignment == 0;
        if (!direct && (!geometry.vertices().allowed().contains(HostExecution.Access.TRANSFER_READ)
            || (source.usage() & VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT) == 0))
            throw new IllegalArgumentException("Producer range supports neither aligned shader access nor a graphics copy");
        Decoded result = new Decoded(window, geometry);
        try {
            int primitives = geometry.metadata().size();
            result.source = direct ? source : result.allocateSource(Math.toIntExact(source.length())).view();
            result.uniform = result.mapped(R2Abi.DispatchRecord.SIZE, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            try (var mapping = result.uniform.map(0, R2Abi.DispatchRecord.SIZE)) {
                R2Abi.DispatchRecord.control(mapping.data().order(ByteOrder.LITTLE_ENDIAN), 0,
                    geometry.vertexCount(), geometry.layout().ordinal(), geometry.topology().ordinal(),
                    (geometry.tintMode()==ProducerResources.TintMode.UNLIT_VERTEX_COLOR?1:0)|(geometry.uniformPrimitives()?2:0));
            }
            int tintBytes=Math.multiplyExact(Math.multiplyExact(primitives,geometry.topology().verticesPerPrimitive()),4);
            result.tint = result.allocate(tintBytes, STORAGE);
            result.facts = result.allocate(Math.multiplyExact(primitives, R2Abi.TriangleRecord.SIZE), STORAGE);
            result.surfaces = result.allocate(surfaceRecords.remaining(), STORAGE);
            result.positions = result.allocate(Math.multiplyExact(geometry.vertexCount(), R2Abi.PositionRecord.SIZE), BUILD);
            result.corners = result.allocate(Math.multiplyExact(geometry.vertexCount(), R2Abi.CornerRecord.SIZE), STORAGE);
            result.indices = result.allocate(Math.multiplyExact(geometry.triangleCount(), 12), BUILD);
            result.triangles = result.allocate(Math.multiplyExact(geometry.triangleCount(), R2Abi.TriangleRecord.SIZE), STORAGE);
            var tint = bytes(tintBytes);
            var facts = bytes(Math.multiplyExact(primitives, R2Abi.TriangleRecord.SIZE));
            for (int i = 0; i < primitives; i++) {
                var primitive = geometry.metadata().get(i);
                for(int corner=0;corner<geometry.topology().verticesPerPrimitive();corner++)
                    tint.putInt((i*geometry.topology().verticesPerPrimitive()+corner)*4,primitive.tint(corner));
                var key = primitive.part();
                R2Abi.TriangleRecord.indicesSurface(facts,i*R2Abi.TriangleRecord.SIZE,primitive.active()?1:0,0,0,0);
                R2Abi.TriangleRecord.partIdentity(facts, i * R2Abi.TriangleRecord.SIZE,
                    (int)key.high(), (int)(key.high() >>> 32), (int)key.low(), (int)(key.low() >>> 32));
                R2Abi.TriangleRecord.ordinalSource(facts, i * R2Abi.TriangleRecord.SIZE,
                    (int)primitive.ordinal(), (int)(primitive.ordinal() >>> 32), 0, -1);
            }
            result.transfers.add(uploads.stage(result.tint, 0, tint));
            result.transfers.add(uploads.stage(result.facts, 0, facts));
            result.transfers.add(uploads.stage(result.surfaces, 0, surfaceRecords));
            return result;
        } catch (RuntimeException | Error failure) { VulkanRetirement.suppress(failure, result::close); throw failure; }
    }

    private static ByteBuffer bytes(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }

    public final class Decoded implements AutoCloseable {
        private final HostExecution.Window window;
        private final ProducerResources.Geometry input;
        private final List<VulkanResources.Buffer> buffers = new ArrayList<>();
        private final List<VulkanUploads.Transfer> transfers = new ArrayList<>();
        private VulkanResources.Buffer uniform, tint, facts, surfaces, sourceCopy, positions, corners, indices, triangles;
        private ResourceViews.Buffer source;
        private long copiedSourceBytes;
        private boolean recorded, closed;
        private Decoded(HostExecution.Window window, ProducerResources.Geometry input) { this.window = window; this.input = input; }
        private VulkanResources.Buffer allocate(int bytes, int usage) {
            var buffer = resources.allocateDevice(bytes, usage); buffers.add(buffer); return buffer;
        }
        private VulkanResources.Buffer allocateSource(int bytes) { copiedSourceBytes=bytes;sourceCopy = allocate(bytes, STORAGE); return sourceCopy; }
        private VulkanResources.Buffer mapped(int bytes, int usage) {
            var buffer = resources.allocateMapped(bytes, usage); buffers.add(buffer); return buffer;
        }
        public void record(VkCommandBuffer command) {
            requireOpen();
            if (recorded || resources.recording() != window.recording() || command.getDevice() != resources.device())
                throw new IllegalStateException("Expired or repeated producer decode");
            for (var buffer : buffers) buffer.markUsed();
            uploads.recordBatch(command, transfers);
            try (var stack = MemoryStack.stackPush()) {
                VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK13.VK_ACCESS_2_MEMORY_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK13.VK_ACCESS_2_TRANSFER_READ_BIT | VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_SHADER_WRITE_BIT));
                if (sourceCopy != null) {
                    var view = input.vertices().view();
                    VK12.vkCmdCopyBuffer(command, view.handle(), source.handle(), VkBufferCopy.calloc(1, stack)
                        .srcOffset(view.offset()).dstOffset(source.offset()).size(source.length()));
                    VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK13.VK_ACCESS_2_SHADER_READ_BIT));
                }
                var writes = VkWriteDescriptorSet.calloc(9, stack);
                VulkanDescriptors.writeUniform(writes.get(0), stack, 0, uniform.view());
                var views = List.of(source, tint.view(), facts.view(), surfaces.view(), positions.view(), corners.view(), indices.view(), triangles.view());
                for (int i = 0; i < views.size(); i++) VulkanDescriptors.writeStorageBuffer(writes.get(i+1), stack, i+1, views.get(i));
                KHRPushDescriptor.vkCmdPushDescriptorSetKHR(command, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, program.layout(), 0, writes);
                program.dispatch(command, 0, (input.vertexCount()+63)/64, 1);
                VulkanBarriers.record(command, stack, new VulkanBarriers.Scope(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK13.VK_ACCESS_2_SHADER_WRITE_BIT, KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                        | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                    VK13.VK_ACCESS_2_SHADER_READ_BIT | VK13.VK_ACCESS_2_TRANSFER_READ_BIT | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR));
            }
            recorded = true;

            for(var scratch:new VulkanResources.Buffer[]{uniform,tint,facts,sourceCopy})if(scratch!=null) {
                scratch.close();buffers.remove(scratch);
            }
            uniform=null;tint=null;facts=null;sourceCopy=null;source=null;transfers.clear();
        }
        public ProducerResources.Geometry input() { requireOpen(); return input; }
        public VulkanResources.Buffer positions() { requireOpen(); return positions; }
        public VulkanResources.Buffer corners() { requireOpen(); return corners; }
        public VulkanResources.Buffer indices() { requireOpen(); return indices; }
        public VulkanResources.Buffer triangles() { requireOpen(); return triangles; }
        public VulkanResources.Buffer surfaces() { requireOpen(); return surfaces; }
        public TriangleGeometry buildInput(boolean opaque) {
            requireOpen();
            return new TriangleGeometry(positions.view(), R2Abi.PositionRecord.SIZE, input.vertexCount(), indices.view(), 4,
                input.triangleCount(), input.revision(), opaque);
        }
        public long copiedSourceBytes() { requireOpen(); return copiedSourceBytes; }
        public long logicalBytes() { requireOpen(); return buffers.stream().mapToLong(b -> b.view().length()).sum(); }
        public void markUsed() { requireOpen(); for (var buffer : buffers) buffer.markUsed(); }
        private void requireOpen() { if (closed) throw new IllegalStateException("Retired producer derivative"); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            for (var buffer : buffers) failure = VulkanRetirement.attempt(failure, buffer::close);
            buffers.clear(); transfers.clear();
            VulkanRetirement.finish(failure);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Throwable failure = VulkanRetirement.attempt(null, program::close);
        failure = VulkanRetirement.attempt(failure, uploads::close);
        VulkanRetirement.finish(failure);
    }
}
