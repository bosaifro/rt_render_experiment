package dev.rt_render_experiment.engine.abi;
import java.nio.ByteBuffer;
import java.util.List;
public final class R2Abi {
    private R2Abi() {}
    public static final String FAMILY = "rt_render_experiment-r2";
    public static final int VERSION = 1;
    public static final int SEMANTIC_API_VERSION = 1;
    public static final String SCHEMA_SHA256 = "c75fab4957ce23ce0dde412913163816acd329ad2f4f8dda320dc6f8f8c55b1b";
    public static final int PRODUCER_API_VERSION = 1;
    public static final String PRODUCER_SCHEMA_SHA256 = "770a4a21f7c6daa59ec2b33b9d43390c705f30fd0300efdf9ebaea687531dd86";
    public static final class FrameRecord {
        private FrameRecord() {}
        public static final int SIZE = 688;
        public static final int INVERSEVIEWPROJECTION = 0;
        public static void inverseViewProjection(ByteBuffer target, int base, float... values) {
            if (values.length != 16) throw new IllegalArgumentException("FrameRecord.inverseViewProjection requires 16 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int VIEWPROJECTION = 64;
        public static void viewProjection(ByteBuffer target, int base, float... values) {
            if (values.length != 16) throw new IllegalArgumentException("FrameRecord.viewProjection requires 16 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 64 + i * 4, values[i]);
        }
        public static final int PREVIOUSVIEWPROJECTION = 128;
        public static void previousViewProjection(ByteBuffer target, int base, float... values) {
            if (values.length != 16) throw new IllegalArgumentException("FrameRecord.previousViewProjection requires 16 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 128 + i * 4, values[i]);
        }
        public static final int ANCHOR = 192;
        public static void anchor(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.anchor requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 192 + i * 4, values[i]);
        }
        public static final int ANCHOROFFSET = 208;
        public static void anchorOffset(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.anchorOffset requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 208 + i * 4, values[i]);
        }
        public static final int EYE = 224;
        public static void eye(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.eye requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 224 + i * 4, values[i]);
        }
        public static final int SUN = 240;
        public static void sun(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.sun requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 240 + i * 4, values[i]);
        }
        public static final int MOON = 256;
        public static void moon(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.moon requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 256 + i * 4, values[i]);
        }
        public static final int ENVIRONMENT = 272;
        public static void environment(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.environment requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 272 + i * 4, values[i]);
        }
        public static final int CLOUDGEOMETRY = 288;
        public static void cloudGeometry(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.cloudGeometry requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 288 + i * 4, values[i]);
        }
        public static final int CLOUDFIELD = 304;
        public static void cloudField(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.cloudField requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 304 + i * 4, values[i]);
        }
        public static final int EXTENT = 320;
        public static void extent(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.extent requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 320 + i * 4, values[i]);
        }
        public static final int OUTPUTEXTENT = 336;
        public static void outputExtent(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.outputExtent requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 336 + i * 4, values[i]);
        }
        public static final int CONTROL = 352;
        public static void control(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.control requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 352 + i * 4, values[i]);
        }
        public static final int COUNTS = 368;
        public static void counts(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.counts requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 368 + i * 4, values[i]);
        }
        public static final int PREVIOUSCAMERA = 384;
        public static void previousCamera(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.previousCamera requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 384 + i * 4, values[i]);
        }
        public static final int JITTER = 400;
        public static void jitter(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.jitter requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 400 + i * 4, values[i]);
        }
        public static final int PRESENTATION = 416;
        public static void presentation(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.presentation requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 416 + i * 4, values[i]);
        }
        public static final int DEPTHRANGE = 432;
        public static void depthRange(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FrameRecord.depthRange requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 432 + i * 4, values[i]);
        }
        public static final int GEOMETRIES = 448;
        public static void geometries(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("FrameRecord.geometries requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 448 + i * 8, values[i]);
        }
        public static final int SOURCES = 456;
        public static void sources(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("FrameRecord.sources requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 456 + i * 8, values[i]);
        }
        public static final int MATERIALS = 464;
        public static void materials(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("FrameRecord.materials requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 464 + i * 8, values[i]);
        }
        public static final int ATMOSPHERE = 472;
        public static void atmosphere(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("FrameRecord.atmosphere requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 472 + i * 8, values[i]);
        }
        public static final int INITIALMEDIUMIDENTITY = 480;
        public static void initialMediumIdentity(ByteBuffer target, int base, int... values) {
            if (values.length != 16) throw new IllegalArgumentException("FrameRecord.initialMediumIdentity requires 16 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 480 + i * 4, values[i]);
        }
        public static final int INITIALMEDIUMOPTICAL = 544;
        public static void initialMediumOptical(ByteBuffer target, int base, float... values) {
            if (values.length != 16) throw new IllegalArgumentException("FrameRecord.initialMediumOptical requires 16 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 544 + i * 4, values[i]);
        }
        public static final int INITIALMEDIUMSCATTERING = 608;
        public static void initialMediumScattering(ByteBuffer target, int base, float... values) {
            if (values.length != 16) throw new IllegalArgumentException("FrameRecord.initialMediumScattering requires 16 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 608 + i * 4, values[i]);
        }
        public static final int INITIALMEDIUMCLEARANCE = 672;
        public static void initialMediumClearance(ByteBuffer target, int base, float... values) {
            if (values.length != 1) throw new IllegalArgumentException("FrameRecord.initialMediumClearance requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 672 + i * 4, values[i]);
        }
        public static final int EMITTERS = 680;
        public static void emitters(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("FrameRecord.emitters requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 680 + i * 8, values[i]);
        }
    }
    public static final class HitRecord {
        private HitRecord() {}
        public static final int SIZE = 32;
        public static final int GEOMETRY = 0;
        public static void geometry(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("HitRecord.geometry requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 0 + i * 4, values[i]);
        }
        public static final int PRIMITIVE = 4;
        public static void primitive(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("HitRecord.primitive requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 4 + i * 4, values[i]);
        }
        public static final int FLAGS = 8;
        public static void flags(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("HitRecord.flags requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 8 + i * 4, values[i]);
        }
        public static final int RESERVED = 12;
        public static void reserved(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("HitRecord.reserved requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 12 + i * 4, values[i]);
        }
        public static final int BARYCENTRICS = 16;
        public static void barycentrics(ByteBuffer target, int base, float... values) {
            if (values.length != 2) throw new IllegalArgumentException("HitRecord.barycentrics requires 2 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int DISTANCE = 24;
        public static void distance(ByteBuffer target, int base, float... values) {
            if (values.length != 1) throw new IllegalArgumentException("HitRecord.distance requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 24 + i * 4, values[i]);
        }
        public static final int FOOTPRINT = 28;
        public static void footprint(ByteBuffer target, int base, float... values) {
            if (values.length != 1) throw new IllegalArgumentException("HitRecord.footprint requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 28 + i * 4, values[i]);
        }
    }
    public static final class SignalRecord {
        private SignalRecord() {}
        public static final int SIZE = 80;
        public static final int DIFFUSE = 0;
        public static void diffuse(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SignalRecord.diffuse requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int GLOSSY = 16;
        public static void glossy(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SignalRecord.glossy requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int MEDIA = 32;
        public static void media(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SignalRecord.media requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
        public static final int DETERMINISTIC = 48;
        public static void deterministic(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SignalRecord.deterministic requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
        public static final int TRANSMITTANCE = 64;
        public static void transmittance(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SignalRecord.transmittance requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 64 + i * 4, values[i]);
        }
    }
    public static final class GuideRecord {
        private GuideRecord() {}
        public static final int SIZE = 144;
        public static final int NORMALDEPTH = 0;
        public static void normalDepth(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("GuideRecord.normalDepth requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int DIFFUSEALBEDO = 16;
        public static void diffuseAlbedo(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("GuideRecord.diffuseAlbedo requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int SPECULARALBEDO = 32;
        public static void specularAlbedo(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("GuideRecord.specularAlbedo requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
        public static final int BOUNDARYDIFFUSEDEPTH = 48;
        public static void boundaryDiffuseDepth(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("GuideRecord.boundaryDiffuseDepth requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
        public static final int BOUNDARYSPECULAR = 64;
        public static void boundarySpecular(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("GuideRecord.boundarySpecular requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 64 + i * 4, values[i]);
        }
        public static final int BOUNDARYNORMALROUGHNESS = 80;
        public static void boundaryNormalRoughness(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("GuideRecord.boundaryNormalRoughness requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 80 + i * 4, values[i]);
        }
        public static final int MOTION = 96;
        public static void motion(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("GuideRecord.motion requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 96 + i * 4, values[i]);
        }
        public static final int IDENTITY = 112;
        public static void identity(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("GuideRecord.identity requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 112 + i * 4, values[i]);
        }
        public static final int PATH = 128;
        public static void path(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("GuideRecord.path requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 128 + i * 4, values[i]);
        }
    }
    public static final class HistoryRecord {
        private HistoryRecord() {}
        public static final int SIZE = 128;
        public static final int DIFFUSE = 0;
        public static void diffuse(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("HistoryRecord.diffuse requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int GLOSSY = 16;
        public static void glossy(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("HistoryRecord.glossy requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int MEDIA = 32;
        public static void media(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("HistoryRecord.media requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
        public static final int MOMENTSA = 48;
        public static void momentsA(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("HistoryRecord.momentsA requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
        public static final int MOMENTSB = 64;
        public static void momentsB(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("HistoryRecord.momentsB requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 64 + i * 4, values[i]);
        }
        public static final int NORMALDEPTH = 80;
        public static void normalDepth(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("HistoryRecord.normalDepth requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 80 + i * 4, values[i]);
        }
        public static final int IDENTITY = 96;
        public static void identity(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("HistoryRecord.identity requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 96 + i * 4, values[i]);
        }
        public static final int PATH = 112;
        public static void path(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("HistoryRecord.path requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 112 + i * 4, values[i]);
        }
    }
    public static final class FilterRecord {
        private FilterRecord() {}
        public static final int SIZE = 48;
        public static final int DIFFUSE = 0;
        public static void diffuse(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FilterRecord.diffuse requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int GLOSSY = 16;
        public static void glossy(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FilterRecord.glossy requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int MEDIA = 32;
        public static void media(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("FilterRecord.media requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
    }
    public static final class LightHistoryRecord {
        private LightHistoryRecord() {}
        public static final int SIZE = 64;
        public static final int NORMALDISTANCE = 0;
        public static void normalDistance(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("LightHistoryRecord.normalDistance requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int RECEIVERCELL = 16;
        public static void receiverCell(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("LightHistoryRecord.receiverCell requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 16 + i * 4, values[i]);
        }
        public static final int IDENTITYSAMPLE = 32;
        public static void identitySample(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("LightHistoryRecord.identitySample requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 32 + i * 4, values[i]);
        }
        public static final int WEIGHTFRAMES = 48;
        public static void weightFrames(ByteBuffer target, int base, float... values) {
            if (values.length != 2) throw new IllegalArgumentException("LightHistoryRecord.weightFrames requires 2 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
    }
    public static final class DispatchRecord {
        private DispatchRecord() {}
        public static final int SIZE = 16;
        public static final int CONTROL = 0;
        public static void control(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("DispatchRecord.control requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 0 + i * 4, values[i]);
        }
    }
    public static final class TextureCopyRecord {
        private TextureCopyRecord() {}
        public static final int SIZE = 16;
        public static final int EXTENTMIPOFFSET = 0;
        public static void extentMipOffset(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("TextureCopyRecord.extentMipOffset requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 0 + i * 4, values[i]);
        }
    }
    public static final class PositionRecord {
        private PositionRecord() {}
        public static final int SIZE = 16;
        public static final int VALUE = 0;
        public static void value(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PositionRecord.value requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
    }
    public static final class CornerRecord {
        private CornerRecord() {}
        public static final int SIZE = 96;
        public static final int UV = 0;
        public static void uv(ByteBuffer target, int base, float... values) {
            if (values.length != 2) throw new IllegalArgumentException("CornerRecord.uv requires 2 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int MATERIAL = 8;
        public static void material(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("CornerRecord.material requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 8 + i * 4, values[i]);
        }
        public static final int PROPERTIES = 12;
        public static void properties(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("CornerRecord.properties requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 12 + i * 4, values[i]);
        }
        public static final int TINT = 16;
        public static void tint(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("CornerRecord.tint requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int NORMAL = 32;
        public static void normal(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("CornerRecord.normal requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
        public static final int TANGENT = 48;
        public static void tangent(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("CornerRecord.tangent requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
        public static final int LAYERUV = 64;
        public static void layerUv(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("CornerRecord.layerUv requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 64 + i * 4, values[i]);
        }
        public static final int EMISSIONTINT = 80;
        public static void emissionTint(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("CornerRecord.emissionTint requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 80 + i * 4, values[i]);
        }
    }
    public static final class TriangleRecord {
        private TriangleRecord() {}
        public static final int SIZE = 48;
        public static final int INDICESSURFACE = 0;
        public static void indicesSurface(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("TriangleRecord.indicesSurface requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 0 + i * 4, values[i]);
        }
        public static final int PARTIDENTITY = 16;
        public static void partIdentity(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("TriangleRecord.partIdentity requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 16 + i * 4, values[i]);
        }
        public static final int ORDINALSOURCE = 32;
        public static void ordinalSource(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("TriangleRecord.ordinalSource requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 32 + i * 4, values[i]);
        }
    }
    public static final class SurfaceRecord {
        private SurfaceRecord() {}
        public static final int SIZE = 128;
        public static final int DEFINITION = 0;
        public static void definition(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("SurfaceRecord.definition requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 0 + i * 4, values[i]);
        }
        public static final int COVERAGE = 16;
        public static void coverage(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SurfaceRecord.coverage requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int TEXTURES = 32;
        public static void textures(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("SurfaceRecord.textures requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 32 + i * 4, values[i]);
        }
        public static final int COLORFACTOR = 48;
        public static void colorFactor(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SurfaceRecord.colorFactor requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
        public static final int EMISSIONFACTOR = 64;
        public static void emissionFactor(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SurfaceRecord.emissionFactor requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 64 + i * 4, values[i]);
        }
        public static final int MEDIUM = 80;
        public static void medium(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("SurfaceRecord.medium requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 80 + i * 8, values[i]);
        }
        public static final int REVISION = 88;
        public static void revision(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("SurfaceRecord.revision requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 88 + i * 8, values[i]);
        }
        public static final int HOSTOCCLUSION = 96;
        public static void hostOcclusion(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("SurfaceRecord.hostOcclusion requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 96 + i * 4, values[i]);
        }
        public static final int BOUNDARY = 100;
        public static void boundary(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("SurfaceRecord.boundary requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 100 + i * 4, values[i]);
        }
        public static final int MEDIUMDEFINITION = 104;
        public static void mediumDefinition(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("SurfaceRecord.mediumDefinition requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 104 + i * 8, values[i]);
        }
        public static final int LAYERS = 112;
        public static void layers(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("SurfaceRecord.layers requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 112 + i * 4, values[i]);
        }
    }
    public static final class GeometryRecord {
        private GeometryRecord() {}
        public static final int SIZE = 240;
        public static final int POSITIONS = 0;
        public static void positions(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("GeometryRecord.positions requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 0 + i * 8, values[i]);
        }
        public static final int CORNERS = 8;
        public static void corners(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("GeometryRecord.corners requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 8 + i * 8, values[i]);
        }
        public static final int TRIANGLES = 16;
        public static void triangles(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("GeometryRecord.triangles requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 16 + i * 8, values[i]);
        }
        public static final int SURFACES = 24;
        public static void surfaces(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("GeometryRecord.surfaces requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 24 + i * 8, values[i]);
        }
        public static final int CURRENT = 32;
        public static void current(ByteBuffer target, int base, float... values) {
            if (values.length != 12) throw new IllegalArgumentException("GeometryRecord.current requires 12 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
        public static final int INVERSE = 80;
        public static void inverse(ByteBuffer target, int base, float... values) {
            if (values.length != 12) throw new IllegalArgumentException("GeometryRecord.inverse requires 12 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 80 + i * 4, values[i]);
        }
        public static final int PREVIOUS = 128;
        public static void previous(ByteBuffer target, int base, float... values) {
            if (values.length != 12) throw new IllegalArgumentException("GeometryRecord.previous requires 12 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 128 + i * 4, values[i]);
        }
        public static final int IDENTITY = 176;
        public static void identity(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("GeometryRecord.identity requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 176 + i * 4, values[i]);
        }
        public static final int REVISIONS = 192;
        public static void revisions(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("GeometryRecord.revisions requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 192 + i * 4, values[i]);
        }
        public static final int STATE = 208;
        public static void state(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("GeometryRecord.state requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 208 + i * 4, values[i]);
        }
        public static final int PREVIOUSPOSITIONS = 224;
        public static void previousPositions(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("GeometryRecord.previousPositions requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 224 + i * 8, values[i]);
        }
    }
    public static final class SourceRecord {
        private SourceRecord() {}
        public static final int SIZE = 144;
        public static final int CENTERRADIUS = 0;
        public static void centerRadius(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceRecord.centerRadius requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int NORMALAREA = 16;
        public static void normalArea(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceRecord.normalArea requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int TANGENTSUPPORT = 32;
        public static void tangentSupport(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceRecord.tangentSupport requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
        public static final int IMPORTANCE = 48;
        public static void importance(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceRecord.importance requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
        public static final int DEFINITION = 64;
        public static void definition(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceRecord.definition requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 64 + i * 4, values[i]);
        }
        public static final int ASSOCIATION = 80;
        public static void association(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceRecord.association requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 80 + i * 4, values[i]);
        }
        public static final int IDENTITY = 96;
        public static void identity(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceRecord.identity requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 96 + i * 4, values[i]);
        }
        public static final int CELL = 112;
        public static void cell(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceRecord.cell requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 112 + i * 4, values[i]);
        }
        public static final int PUBLICATION = 128;
        public static void publication(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceRecord.publication requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 128 + i * 4, values[i]);
        }
    }
    public static final class LightCellRecord {
        private LightCellRecord() {}
        public static final int SIZE = 32;
        public static final int COORDINATECOUNT = 0;
        public static void coordinateCount(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("LightCellRecord.coordinateCount requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 0 + i * 4, values[i]);
        }
        public static final int RANGE = 16;
        public static void range(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("LightCellRecord.range requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 16 + i * 4, values[i]);
        }
    }
    public static final class SourceTableRecord {
        private SourceTableRecord() {}
        public static final int SIZE = 32;
        public static final int SOURCES = 0;
        public static void sources(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("SourceTableRecord.sources requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 0 + i * 8, values[i]);
        }
        public static final int CELLS = 8;
        public static void cells(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("SourceTableRecord.cells requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 8 + i * 8, values[i]);
        }
        public static final int COUNTS = 16;
        public static void counts(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("SourceTableRecord.counts requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 16 + i * 4, values[i]);
        }
    }
    public static final class MaterialRecord {
        private MaterialRecord() {}
        public static final int SIZE = 128;
        public static final int CLASSIFICATION = 0;
        public static void classification(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("MaterialRecord.classification requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 0 + i * 4, values[i]);
        }
        public static final int TINTOXIDATION = 16;
        public static void tintOxidation(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("MaterialRecord.tintOxidation requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int FINISH = 32;
        public static void finish(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("MaterialRecord.finish requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
        public static final int CONDUCTORETATRANSMISSION = 48;
        public static void conductorEtaTransmission(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("MaterialRecord.conductorEtaTransmission requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
        public static final int CONDUCTORKTHICKNESS = 64;
        public static void conductorKThickness(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("MaterialRecord.conductorKThickness requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 64 + i * 4, values[i]);
        }
        public static final int ABSORPTIONEMISSIONFLOOR = 80;
        public static void absorptionEmissionFloor(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("MaterialRecord.absorptionEmissionFloor requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 80 + i * 4, values[i]);
        }
        public static final int MODIFIERS = 96;
        public static void modifiers(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("MaterialRecord.modifiers requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 96 + i * 4, values[i]);
        }
        public static final int EMISSIONSPECTRUMINTENSITY = 112;
        public static void emissionSpectrumIntensity(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("MaterialRecord.emissionSpectrumIntensity requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 112 + i * 4, values[i]);
        }
    }
    public static final class AtmosphereCellRecord {
        private AtmosphereCellRecord() {}
        public static final int SIZE = 16;
        public static final int DENSITY = 0;
        public static void density(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("AtmosphereCellRecord.density requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
    }
    public static final class MediumRecord {
        private MediumRecord() {}
        public static final int SIZE = 48;
        public static final int IDENTITY = 0;
        public static void identity(ByteBuffer target, int base, long... values) {
            if (values.length != 1) throw new IllegalArgumentException("MediumRecord.identity requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putLong(base + 0 + i * 8, values[i]);
        }
        public static final int KIND = 8;
        public static void kind(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("MediumRecord.kind requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 8 + i * 4, values[i]);
        }
        public static final int RESERVED = 12;
        public static void reserved(ByteBuffer target, int base, int... values) {
            if (values.length != 1) throw new IllegalArgumentException("MediumRecord.reserved requires 1 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 12 + i * 4, values[i]);
        }
        public static final int OPTICAL = 16;
        public static void optical(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("MediumRecord.optical requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int SCATTERING = 32;
        public static void scattering(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("MediumRecord.scattering requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
    }
    public static final class EmitterRecord {
        private EmitterRecord() {}
        public static final int SIZE = 64;
        public static final int POSITIONRADIUS = 0;
        public static void positionRadius(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("EmitterRecord.positionRadius requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int RADIANCEAREA = 16;
        public static void radianceArea(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("EmitterRecord.radianceArea requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int SAMPLING = 32;
        public static void sampling(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("EmitterRecord.sampling requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
        public static final int IDENTITY = 48;
        public static void identity(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("EmitterRecord.identity requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 48 + i * 4, values[i]);
        }
    }
    public static final class PrimaryWorkRecord {
        private PrimaryWorkRecord() {}
        public static final int SIZE = 144;
        public static final int RAYDIRECTIONMIN = 0;
        public static void rayDirectionMin(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PrimaryWorkRecord.rayDirectionMin requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int HITBARYDISTANCEMAX = 16;
        public static void hitBaryDistanceMax(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PrimaryWorkRecord.hitBaryDistanceMax requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int HITIDENTITY = 32;
        public static void hitIdentity(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("PrimaryWorkRecord.hitIdentity requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 32 + i * 4, values[i]);
        }
        public static final int RESERVED = 48;
        public static void reserved(ByteBuffer target, int base, float... values) {
            if (values.length != 20) throw new IllegalArgumentException("PrimaryWorkRecord.reserved requires 20 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
        public static final int POLICY = 128;
        public static void policy(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("PrimaryWorkRecord.policy requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 128 + i * 4, values[i]);
        }
    }
    public static final class PathWorkRecord {
        private PathWorkRecord() {}
        public static final int SIZE = 80;
        public static final int THROUGHPUTDIFFUSE = 0;
        public static void throughputDiffuse(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathWorkRecord.throughputDiffuse requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int THROUGHPUTGLOSSY = 16;
        public static void throughputGlossy(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathWorkRecord.throughputGlossy requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int VERTEXPOSITIONDISTANCE = 32;
        public static void vertexPositionDistance(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathWorkRecord.vertexPositionDistance requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
        public static final int VERTEXVIEWBARYX = 48;
        public static void vertexViewBaryX(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathWorkRecord.vertexViewBaryX requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 48 + i * 4, values[i]);
        }
        public static final int VERTEXLOCATORBARYY = 64;
        public static void vertexLocatorBaryY(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathWorkRecord.vertexLocatorBaryY requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 64 + i * 4, values[i]);
        }
    }
    public static final class PathMediumRecord {
        private PathMediumRecord() {}
        public static final int SIZE = 208;
        public static final int OPTICAL = 0;
        public static void optical(ByteBuffer target, int base, float... values) {
            if (values.length != 40) throw new IllegalArgumentException("PathMediumRecord.optical requires 40 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int PARENTS = 160;
        public static void parents(ByteBuffer target, int base, int... values) {
            if (values.length != 8) throw new IllegalArgumentException("PathMediumRecord.parents requires 8 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 160 + i * 4, values[i]);
        }
        public static final int IDENTITYDEPTH = 192;
        public static void identityDepth(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathMediumRecord.identityDepth requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 192 + i * 4, values[i]);
        }
    }
    public static final class PathResultRecord {
        private PathResultRecord() {}
        public static final int SIZE = 48;
        public static final int DIFFUSEDISTANCE = 0;
        public static void diffuseDistance(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathResultRecord.diffuseDistance requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 0 + i * 4, values[i]);
        }
        public static final int GLOSSYFLAGS = 16;
        public static void glossyFlags(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathResultRecord.glossyFlags requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 16 + i * 4, values[i]);
        }
        public static final int ENDPOINTPOSITIONDISTANCE = 32;
        public static void endpointPositionDistance(ByteBuffer target, int base, float... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathResultRecord.endpointPositionDistance requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putFloat(base + 32 + i * 4, values[i]);
        }
    }
    public static final class PathQueueRecord {
        private PathQueueRecord() {}
        public static final int SIZE = 32;
        public static final int DISPATCHCOUNT = 0;
        public static void dispatchCount(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathQueueRecord.dispatchCount requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 0 + i * 4, values[i]);
        }
        public static final int ITERATION = 16;
        public static void iteration(ByteBuffer target, int base, int... values) {
            if (values.length != 4) throw new IllegalArgumentException("PathQueueRecord.iteration requires 4 components");
            if (target.order() != java.nio.ByteOrder.LITTLE_ENDIAN) throw new IllegalArgumentException("GPU records require little endian");
            for (int i = 0; i < values.length; i++) target.putInt(base + 16 + i * 4, values[i]);
        }
    }
    public static final int VIEW_WORLD = 0;
    public static final int VIEW_VIEWMODEL = 1;
    public static final int RAY_CAMERA_PATH = 1;
    public static final int RAY_REFLECTION = 2;
    public static final int RAY_SHADOW = 4;
    public static final int RAY_TRANSPORT = 8;
    public static final int RAY_VIEWMODEL = 16;
    public static final int RAY_RASTER_ONLY = 128;
    public static final int GEOMETRY_SOURCE_DEPTH = 1;
    public static final int LAYER_COVERAGE_TINT = 1;
    public static final int LAYER_TEXTURE_SAMPLER = 2;
    public static final int LAYER_TINT_SRGB = 4;
    public static final int LAYER_EMISSION_TINT_SRGB = 8;
    public static final int MATERIAL_CONDUCTOR = 1;
    public static final int MATERIAL_EMITTER = 2;
    public static final int MATERIAL_DISTANCE_ROUGHNESS = 4;
    public static final int MATERIAL_THIN = 8;
    public static final int ROLE_NONE = 0;
    public static final int ROLE_VOLUME = 1;
    public static final int ROLE_CONTACT = 2;
    public static final int ROLE_CLIP = 3;
    public static final int MEDIUM_AIR = 0;
    public static final int MEDIUM_WATER = 1;
    public static final int MEDIUM_GLASS = 2;
    public static final int MEDIUM_ICE = 3;
    public static final int HIT_HIT = 1;
    public static final int HIT_FRONT = 2;
    public static final int FRAME_PREVIOUS_VALID = 1;
    public static final int FRAME_WRITE_HITS = 2;
    public static final int FRAME_LIGHT_REUSE = 4;
    public static final int FRAME_LIGHT_REUSE_VALID = 8;
    public static final int FRAME_CAMERA_IN_WATER = 16;
    public static final int FRAME_RAW_CLAMP = 64;
    public static final int FRAME_INITIAL_MEDIA = 128;
    public static final int FRAME_CLOUDS_VISIBLE = 256;
    public static final int GUIDE_PATH_SAMPLED = 1;
    public static final int GUIDE_PATH_GLOSSY = 2;
    public static final int GUIDE_PATH_DELTA = 4;
    public static final int GUIDE_PRIMARY_DIELECTRIC = 8;
    public static final int GUIDE_PRIMARY_REFLECTED = 16;
    public static final int GUIDE_PREFIX_TRUNCATED = 32;
    public static final int GUIDE_TERMINAL_HIT = 64;
    public static final int GUIDE_ENDPOINT_VALID = 128;
    public static final int GUIDE_ENDPOINT_HIT = 256;
    public static final int GUIDE_PLANAR_REFLECTION = 512;
    public static final int GUIDE_REFLECTION_MOTION_VALID = 1024;
    public static final int GUIDE_TERMINAL_MOVED = 2048;
    public static final int GUIDE_TERMINAL_MOTION_INVALID = 4096;
    public static final int GUIDE_MEDIUM_STATUS_SHIFT = 13;
    public static final int GUIDE_MEDIUM_STATUS_MASK = 57344;
    public static final int GUIDE_CLIPPED_MEDIUM = 65536;
    public static final int GUIDE_MOTION_VALID = 131072;
    public static final int GUIDE_PRIMARY_HIT = 262144;
    public static final int COVERAGE_OPAQUE = 0;
    public static final int COVERAGE_CUTOUT = 1;
    public static final int COVERAGE_FILTER = 2;
    public static final int COVERAGE_DIELECTRIC = 3;
    public static final int SOURCE_POINT = 0;
    public static final int SOURCE_QUAD = 1;
    public static final int AUTOMATIC_HIT_COVERAGE_MASK = 9;
    public static boolean automaticHitCoverage(int coverage, boolean doubleSided) {
        return doubleSided && coverage >= 0 && coverage < 32 && (AUTOMATIC_HIT_COVERAGE_MASK & (1 << coverage)) != 0;
    }
    public static final int MATERIAL_COUNT = 70;
    public static final int MEDIUM_CAPACITY = 4;
    public static final int ATMOSPHERE_WIDTH = 512;
    public static final int ATMOSPHERE_HEIGHT = 256;
    public static final int RECONSTRUCTION_ITERATIONS = 1;
    public static final int SOURCE_PREPARATION_COLUMNS = 65535;
    public static final int PATH_QUEUE_COLUMNS = 256;
    public static final int PATH_BOUNCES = 1;
    public static final class Formats { private Formats() {}
        public static final int COMPOSITION = org.lwjgl.vulkan.VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
        public static final int DEPTH = org.lwjgl.vulkan.VK12.VK_FORMAT_D32_SFLOAT;
        public static final int RADIANCE = org.lwjgl.vulkan.VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
        public static final int DISPLAY_TARGET = org.lwjgl.vulkan.VK12.VK_FORMAT_R8G8B8A8_UNORM;
    }
    public static final class Transport {
        private Transport() {}
        public static final List<String> ENTRIES = List.of("r2PrimaryVisibility");
        public static final int FRAME = 0;
        public static final int WORLDAS = 1;
        public static final int HITS = 2;
        public static final int SIGNALS = 3;
        public static final int GUIDES = 4;
        public static final int HOSTDEPTH = 5;
        public static final int OUTPUT = 6;
        public static final int PREVIOUSLIGHTHISTORY = 7;
        public static final int CURRENTLIGHTHISTORY = 8;
    }
    public static final class Materials {
        private Materials() {}
        public static final List<String> ENTRIES = List.of("r2CompileMaterials");
        public static final int DEFINITIONS = 0;
    }
    public static final class Atmosphere {
        private Atmosphere() {}
        public static final List<String> ENTRIES = List.of("r2CompileAtmosphere");
        public static final int CELLS = 0;
    }
    public static final class TextureCopy {
        private TextureCopy() {}
        public static final List<String> ENTRIES = List.of("r2TextureCopy");
        public static final int COPY = 0;
        public static final int SOURCE = 1;
        public static final int PIXELS = 2;
    }
    public static final class TextureCopyFloat {
        private TextureCopyFloat() {}
        public static final List<String> ENTRIES = List.of("r2TextureCopyFloat");
        public static final int COPY = 0;
        public static final int SOURCE = 1;
        public static final int PIXELS = 2;
    }
    public static final class Reconstruction {
        private Reconstruction() {}
        public static final List<String> ENTRIES = List.of("r2Temporal", "r2Atrous");
        public static final int FRAME = 0;
        public static final int SIGNALS = 1;
        public static final int GUIDES = 2;
        public static final int PREVIOUS = 3;
        public static final int HISTORY = 4;
        public static final int OUTPUT = 5;
    }
    public static final class Presentation {
        private Presentation() {}
        public static final List<String> ENTRIES = List.of("r2Meter", "r2Display");
        public static final int FRAME = 0;
        public static final int COMPOSITE = 1;
        public static final int PREVIOUSEXPOSURE = 2;
        public static final int EXPOSURE = 3;
        public static final int DISPLAYOUTPUT = 4;
    }
    public static final class Handoff {
        private Handoff() {}
        public static final List<String> ENTRIES = List.of("r2HandoffVertex", "r2WorldHandoff", "r2DisplayHandoff", "r2ViewmodelHandoff");
        public static final int FRAME = 0;
        public static final int COLOR = 1;
        public static final int HOSTDEPTH = 2;
        public static final int GUIDES = 3;
    }
    public static final class Resolve {
        private Resolve() {}
        public static final List<String> ENTRIES = List.of("r2Resolve");
        public static final int FRAME = 0;
        public static final int INPUT = 1;
        public static final int OUTPUT = 2;
    }
    public static final class Import {
        private Import() {}
        public static final List<String> ENTRIES = List.of("r2Import");
        public static final int FRAME = 0;
        public static final int COMPOSITION = 1;
        public static final int COLOR = 2;
    }
    public static final class SourcePreparation {
        private SourcePreparation() {}
        public static final List<String> ENTRIES = List.of("r2PrepareSources");
        public static final int FRAME = 0;
    }
    public static final class ProducerDecode {
        private ProducerDecode() {}
        public static final List<String> ENTRIES = List.of("r2DecodeProducer");
        public static final int DECODE = 0;
        public static final int SOURCE = 1;
        public static final int TINT = 2;
        public static final int FACTS = 3;
        public static final int SURFACES = 4;
        public static final int POSITIONS = 5;
        public static final int CORNERS = 6;
        public static final int INDICES = 7;
        public static final int TRIANGLES = 8;
    }
    public static final List<String> OPTICAL_ENTRIES = List.of("r2PrimaryVisibility", "r2CompileMaterials", "r2CompileAtmosphere", "r2TextureCopy", "r2TextureCopyFloat", "r2Temporal", "r2Atrous", "r2Meter", "r2Display", "r2HandoffVertex", "r2WorldHandoff", "r2DisplayHandoff", "r2ViewmodelHandoff", "r2Resolve", "r2Import", "r2PrepareSources");
}
