package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.*;
import dev.rt_render_experiment.engine.abi.R2Abi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.lwjgl.vulkan.VK12;
import static org.junit.jupiter.api.Assertions.*;

final class R2ProgramGpuTest {
    private static final int QUADS = 24;

    private static Path packageDirectory() {
        String declared = System.getProperty("rt_render_experiment.shaderPackage");
        Assumptions.assumeTrue(declared != null, "shader package location was not supplied");
        Path directory = Path.of(declared);
        Assumptions.assumeTrue(java.nio.file.Files.isDirectory(directory), "shader package is not built");
        return directory;
    }

    private static HostExecution.Capabilities capabilities() {
        return new HostExecution.Capabilities(1, Set.of(HostExecution.Capability.BUFFER_ADDRESS,
            HostExecution.Capability.ACCELERATION_STRUCTURE, HostExecution.Capability.RAY_QUERY), 256, 1_000_000);
    }

    private static void writeGround(ByteBuffer bytes, SceneInputs.Corner sample) {
        int index = 0;
        for (int column = 0; column < QUADS; column++) {
            float x0 = -6.0f + column * 0.5f, x1 = x0 + 0.5f;
            float[][] corners = { {x0, -1.2f, -2.0f}, {x1, -1.2f, -2.0f}, {x1, -1.2f, -9.0f}, {x0, -1.2f, -9.0f} };
            for (float[] corner : corners) {
                int base = index * 28;
                bytes.putFloat(base, corner[0]).putFloat(base + 4, corner[1]).putFloat(base + 8, corner[2]);
                bytes.putInt(base + 12, 0xffffffff);
                bytes.putFloat(base + 16, sample.u()).putFloat(base + 20, sample.v());
                bytes.putInt(base + 24, (13 << 4) | ((15 << 4) << 16));
                index++;
            }
        }
    }

    private static SceneInputs.Vec3 unit(float x, float y, float z) {
        float length = (float)Math.sqrt(x * x + y * y + z * z);
        return new SceneInputs.Vec3(x / length, y / length, z / length);
    }

    private static RenderFrame frame(int width, int height, int sample) {
        var projection = new org.joml.Matrix4f().perspective((float)Math.PI / 2, (float)width / (float)height, 0.1f, 512, true);
        var sun = unit(0.35f, 0.80f, 0.49f);
        var environment = new RenderFrame.Environment(sun, unit(-sun.x(), -sun.y(), -sun.z()), 0.25f, 0, 0, false, 64, 128, false);
        return new RenderFrame(width, height, sample, new SceneInputs.Origin(0, 64, 0),
            CameraFixtures.rows(new org.joml.Matrix4f(projection).invert()), CameraFixtures.rows(projection),
            CameraFixtures.rows(projection), environment, RenderFrame.DepthConvention.FORWARD);
    }

    private record Rendered(ByteBuffer pixels, ByteBuffer display, int covered, long gpuNanos, long cpuNanos) {}

    private Rendered render(FixtureHost host, R2ShaderPackage shaders, int width, int height, int frames, boolean inspect) throws Exception {
        var capabilities = capabilities();
        try (var scenes = new ProducerGpuScene(host.resources, capabilities, shaders);
             var renderer = new R2Renderer(host.resources, capabilities, shaders);
             var vertices = host.resources.allocateMapped(QUADS * 4 * 28, VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)) {
            var original = SceneFixtures.prepare(false).geometry().getFirst().input();
            var primitive = original.primitives().getFirst();
            try (var mapping = vertices.map(0, QUADS * 4 * 28)) {
                writeGround(mapping.data().order(ByteOrder.LITTLE_ENDIAN), primitive.corners().getFirst());
            }
            var grant = new HostExecution.BufferGrant(vertices.view(), 1, Set.of(HostExecution.Access.TRANSFER_READ), host.resources.recording());
            var metadata = new java.util.ArrayList<ProducerResources.Primitive>();
            for (int i = 0; i < QUADS; i++)
                metadata.add(new ProducerResources.Primitive(primitive.part(), primitive.ordinal() + i, primitive.surface(), 0xffffffff));
            var geometry = new ProducerResources.Geometry(original.key(), 1, 1, 1, 1, 1, grant, ProducerResources.Layout.BLOCK28,
                ProducerResources.Topology.QUADS_012_230, QUADS * 4, original.origin(), original.current(), original.previous(),
                original.participation(), List.copyOf(metadata));
            var registry = new ProducerRegistry(1); registry.publish(geometry);
            ByteBuffer pixels = null, displayPixels = null; int covered = 0; long best = Long.MAX_VALUE, cpuBest = Long.MAX_VALUE;
            for (int step = 0; step < frames; step++) {
                var frame = frame(width, height, step).withReconstruction(RenderFrame.Reconstruction.PORTABLE).withWorldLightReuse(false)
                    .withTemporal(new RenderFrame.Temporal(original.origin(), step > 0, 0, 0, 0, 0));
                long serial = host.resources.recording(); long recordStarted = System.nanoTime(); var command = host.begin();
                var window = new HostExecution.Window(1, serial, serial, HostExecution.Stage.SCENE_PREPARATION, step == 0 ? List.of(grant) : List.of());
                if (step == 0) vertices.markUsed();
                try (var prepared = scenes.prepare(window, registry, frame.origin(), List.of(), LightInputs.Publication.empty(), null)) {
                    prepared.record(command);
                    try (var output = renderer.record(new HostExecution.Window(1, serial, serial, HostExecution.Stage.WORLD, List.of()),
                             command, prepared.scene(), frame, inspect)) {
                        assertEquals(RenderFrame.Reconstruction.PORTABLE, output.reconstructionMode(), "portable template stages were not recorded");
                        boolean inspectDisplay = inspect && width * height <= 4096;
                        if (inspectDisplay)
                            renderer.recordPresentation(new HostExecution.Window(1, serial, serial, HostExecution.Stage.DISPLAY, List.of()), command, output);
                        var raw = inspect ? host.copy(command, output.radiance()) : null;
                        var hits = inspect ? host.copy(command, output.hits()) : null;
                        var display = inspectDisplay ? host.copy(command, output.display()) : null;
                        try {
                            cpuBest = Math.min(cpuBest, System.nanoTime() - recordStarted);
                            long started = System.nanoTime();
                            long submitted = host.submit(command);
                            prepared.submitted(submitted); output.submitted(submitted);
                            host.complete(command, submitted);
                            best = Math.min(best, System.nanoTime() - started);
                            if (inspect) {
                                pixels = host.read(raw);
                                var hitBytes = host.read(hits);
                                if (display != null) displayPixels = host.read(display);
                                covered = 0;
                                for (int i = 0; i < width * height; i++) {
                                    if ((hitBytes.getInt(i * R2Abi.HitRecord.SIZE + R2Abi.HitRecord.FLAGS) & R2Abi.HIT_HIT) != 0) {
                                        covered++;
                                    }
                                }
                            } else covered = width * height;
                        } finally { if (raw != null) raw.close(); if (hits != null) hits.close(); if (display != null) display.close(); }
                    }
                }
            }
            return new Rendered(pixels, displayPixels, covered, best, cpuBest);
        }
    }

    @Test void theTemplatePresentsTracedGeometryAndBackground() throws Exception {
        var directory = packageDirectory();
        try (var host = new FixtureHost()) {
            var shaders = R2ShaderPackage.read(directory);
            var rendered = render(host, shaders, 64, 64, 2, true);
            assertTrue(rendered.covered() > 0, "primary rays reached no traced surface");
            assertTrue(rendered.covered() < 64 * 64, "the traced scene left no sky");

            double surface = 0, sky = 0; int surfaceCount = 0, skyCount = 0;
            for (int i = 0; i < 64 * 64; i++) {
                float r = rendered.pixels().getFloat(i * 16), g = rendered.pixels().getFloat(i * 16 + 4), b = rendered.pixels().getFloat(i * 16 + 8);
                assertTrue(Float.isFinite(r) && Float.isFinite(g) && Float.isFinite(b), "non-finite radiance");
                assertTrue(r >= 0 && g >= 0 && b >= 0, "negative radiance");
                for (int channel = 0; channel < 4; channel++) {
                    float value = rendered.display().getFloat(i * 16 + channel * 4);
                    assertTrue(Float.isFinite(value) && value >= 0 && value <= 1, "invalid display output");
                }
                double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                if (i < 64) { sky += luminance; skyCount++; } else { surface += luminance; surfaceCount++; }
            }
            assertTrue(sky / skyCount > 1e-4, "the sky carries no radiance");
            assertTrue(surface / surfaceCount > 1e-4, "the traced surface carries no radiance");
            host.assertValidation();
        }
    }

    @Test void theProgramRendersNativeUltraHighDefinition() throws Exception {
        var directory = packageDirectory();
        Assumptions.assumeTrue(Boolean.getBoolean("rt_render_experiment.measure"), "native 4K measurement was not requested");
        try (var host = new FixtureHost(false)) {
            var shaders = R2ShaderPackage.read(directory);
            var warm = render(host, shaders, 3840, 2160, 3, true);
            assertTrue(warm.covered() > 0, "primary rays reached no traced surface at 3840x2160");
            var rendered = render(host, shaders, 3840, 2160, 24, false);
            System.out.printf("R2 native 3840x2160 gpu %.3f ms; cpu record %.3f ms; with 4K readback %.3f ms; traced coverage %.1f%%%n",
                rendered.gpuNanos() / 1e6, rendered.cpuNanos() / 1e6, warm.gpuNanos() / 1e6, 100.0 * warm.covered() / (3840.0 * 2160.0));
        }
    }
}
