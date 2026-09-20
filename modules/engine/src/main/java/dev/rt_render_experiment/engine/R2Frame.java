package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import dev.rt_render_experiment.engine.abi.R2Abi;


final class R2Frame {
    private R2Frame() {}
    record Tables(long geometries, long sources, long materials, long atmosphere, long sceneRevision, int cloudSlot, int filterPrimitives,long emitters) {}
    static ByteBuffer encode(RenderFrame frame, Tables tables, boolean diagnosticHits, boolean lightReuse, boolean lightReuseValid) {
        if (tables.filterPrimitives() < 0) throw new IllegalArgumentException("Invalid filter scene count");
        ByteBuffer result = ByteBuffer.allocate(R2Abi.FrameRecord.SIZE).order(ByteOrder.LITTLE_ENDIAN);
        R2Abi.FrameRecord.inverseViewProjection(result, 0, frame.inverseViewProjection());
        R2Abi.FrameRecord.viewProjection(result, 0, frame.viewProjection());
        R2Abi.FrameRecord.previousViewProjection(result, 0, frame.previousViewProjection());
        var origin = frame.origin();
        double x = Math.floor(origin.x()), y = Math.floor(origin.y()), z = Math.floor(origin.z());
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Scene anchor exceeds the admitted integer range");
        R2Abi.FrameRecord.anchor(result, 0, (int)x, (int)y, (int)z, 0);
        R2Abi.FrameRecord.anchorOffset(result, 0, (float)(x - origin.x()), (float)(y - origin.y()), (float)(z - origin.z()), 0);
        var eye = frame.camera().eyeOffset();
        R2Abi.FrameRecord.eye(result, 0, eye.x(), eye.y(), eye.z(), CameraProjection.MAXIMUM_RAY_DISTANCE);
        var environment = frame.environment();
        R2Abi.FrameRecord.sun(result, 0, environment.sun().x(), environment.sun().y(), environment.sun().z(), environment.dayFraction());
        R2Abi.FrameRecord.moon(result, 0, environment.moon().x(), environment.moon().y(), environment.moon().z(), environment.rain());
        R2Abi.FrameRecord.environment(result, 0, environment.cloudDisplacement(), 0, environment.referenceAltitude(), environment.sceneRadius());
        R2Abi.FrameRecord.cloudGeometry(result, 0, environment.cloud().baseAltitude(), environment.cloud().thickness(), environment.cloud().cellSize(), 0);
        R2Abi.FrameRecord.cloudField(result, 0, tables.cloudSlot(), environment.cloud().width(), environment.cloud().height(), 0);
        R2Abi.FrameRecord.extent(result, 0, frame.width(), frame.height(), frame.sample(), frame.reconstruction().ordinal());
        R2Abi.FrameRecord.outputExtent(result, 0, frame.outputWidth(), frame.outputHeight(), 0, 0);
        R2Abi.FrameRecord.control(result, 0, R2Abi.VERSION, (int)tables.sceneRevision(), frame.view().identity(), frame.view().primaryMask());
        var temporal = frame.temporal();
        var presentation = frame.presentation();
        var initial = frame.initialMedia();
        int flags = (temporal.valid() ? R2Abi.FRAME_PREVIOUS_VALID : 0) | (diagnosticHits ? R2Abi.FRAME_WRITE_HITS : 0)
            | (lightReuse ? R2Abi.FRAME_LIGHT_REUSE : 0) | (lightReuseValid ? R2Abi.FRAME_LIGHT_REUSE_VALID : 0)
            | (environment.cameraInWater() ? R2Abi.FRAME_CAMERA_IN_WATER : 0)
            | (presentation.rawClamp() ? R2Abi.FRAME_RAW_CLAMP : 0) | (initial.isPresent() ? R2Abi.FRAME_INITIAL_MEDIA : 0)
            | (environment.cloudsVisible() ? R2Abi.FRAME_CLOUDS_VISIBLE : 0);
        R2Abi.FrameRecord.counts(result, 0, flags, tables.filterPrimitives(), frame.clippedMediumPrimitives(), initial.map(o -> o.enclosures().size()).orElse(0));
        R2Abi.FrameRecord.previousCamera(result, 0, (float)(origin.x() - temporal.previousOrigin().x()), (float)(origin.y() - temporal.previousOrigin().y()),
            (float)(origin.z() - temporal.previousOrigin().z()), temporal.valid() ? 1 : 0);
        R2Abi.FrameRecord.jitter(result, 0, temporal.jitterX(), temporal.jitterY(), temporal.previousJitterX(), temporal.previousJitterY());
        R2Abi.FrameRecord.presentation(result, 0, presentation.deltaSeconds(), 0, 0, 0);
        var depth = frame.depthConvention();
        R2Abi.FrameRecord.depthRange(result, 0, depth.near, depth.far, 0, 0);
        R2Abi.FrameRecord.geometries(result, 0, tables.geometries());
        R2Abi.FrameRecord.sources(result, 0, tables.sources());
        R2Abi.FrameRecord.emitters(result,0,tables.emitters());
        R2Abi.FrameRecord.materials(result, 0, tables.materials());
        R2Abi.FrameRecord.atmosphere(result, 0, tables.atmosphere());
        int[] identity = new int[R2Abi.MEDIUM_CAPACITY * 4]; float[] optical = new float[identity.length], scattering = new float[identity.length];
        if (initial.isPresent()) {
            var media = initial.orElseThrow();
            if (media.sceneRevision() != tables.sceneRevision()) throw new IllegalArgumentException("Initial medium belongs to another scene revision");
            for (int i = 0; i < media.enclosures().size(); i++) {
                var enclosure = media.enclosures().get(i); var medium = enclosure.medium(); int offset = i * 4;
                identity[offset] = (int)enclosure.volume(); identity[offset + 1] = (int)(enclosure.volume() >>> 32); identity[offset + 2] = medium.kind().encoding();
                optical[offset] = medium.ior(); optical[offset + 1] = medium.absorption().x(); optical[offset + 2] = medium.absorption().y(); optical[offset + 3] = medium.absorption().z();
                scattering[offset] = medium.scattering().x(); scattering[offset + 1] = medium.scattering().y(); scattering[offset + 2] = medium.scattering().z();
            }
            R2Abi.FrameRecord.initialMediumClearance(result, 0, media.clearance());
        }
        R2Abi.FrameRecord.initialMediumIdentity(result, 0, identity);
        R2Abi.FrameRecord.initialMediumOptical(result, 0, optical);
        R2Abi.FrameRecord.initialMediumScattering(result, 0, scattering);
        return result;
    }
}
