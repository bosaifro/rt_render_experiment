package dev.rt_render_experiment.contract;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;


public final class TextureInputs {
    public static final int API_VERSION = 4;
    private TextureInputs() {}
    public enum Encoding { SRGB, LINEAR }
    public enum Address { CLAMP, REPEAT }
    public enum Filter { NEAREST, LINEAR }
    public record Sampling(Address u,Address v,Filter minification,Filter magnification,Filter mipmap,float maximumLod,float anisotropy) {
        public Sampling {
            Objects.requireNonNull(u);Objects.requireNonNull(v);Objects.requireNonNull(minification);Objects.requireNonNull(magnification);Objects.requireNonNull(mipmap);
            if(!Float.isFinite(maximumLod) || maximumLod<0 || !Float.isFinite(anisotropy) || anisotropy<1)throw new IllegalArgumentException("Invalid texture sampling facts");
        }
        public static Sampling linear(Address address) { return new Sampling(address,address,Filter.LINEAR,Filter.LINEAR,Filter.LINEAR,1000,1); }
    }
    public sealed interface Resource permits Texture,GpuTexture {
        SceneInputs.Key key(); long revision(); Encoding encoding(); Sampling sampling();
    }
    public enum GpuUse { COPY, FRAME_READ }









    public record GpuTexture(SceneInputs.Key key,long revision,Encoding encoding,Sampling sampling,
                             HostExecution.ImageGrant source,GpuUse use) implements Resource {
        public GpuTexture(SceneInputs.Key key,long revision,Encoding encoding,Sampling sampling,HostExecution.ImageGrant source) {
            this(key,revision,encoding,sampling,source,GpuUse.COPY);
        }
        public GpuTexture(SceneInputs.Key key,long revision,Encoding encoding,Address address,HostExecution.ImageGrant source) {
            this(key,revision,encoding,Sampling.linear(address),source);
        }
        public GpuTexture {
            Objects.requireNonNull(key);Objects.requireNonNull(encoding);Objects.requireNonNull(sampling);Objects.requireNonNull(source);Objects.requireNonNull(use);
            if(revision<=0 || revision!=source.contentRevision() || revision!=source.view().contentEpoch()
                || !source.allowed().contains(HostExecution.Access.SHADER_READ))throw new IllegalArgumentException("Invalid texture content grant");
        }

        public void requirePreparationWindow(HostExecution.Window window) {
            if(window==null || window.stage()!=HostExecution.Stage.SCENE_PREPARATION
                || window.device()!=source.view().device() || !window.images().contains(source)
                || source.validThroughRecording()<window.recording()
                || use==GpuUse.FRAME_READ && source.validThroughRecording()!=window.recording())
                throw new IllegalArgumentException("Texture requires its declared preparation/read window and recording");
        }
    }
    public static final class Level {
        private final int width,height;
        private final byte[] pixels;
        public Level(int width,int height,ByteBuffer pixels) {
            if (width<=0 || height<=0 || pixels.remaining()!=Math.multiplyExact(Math.multiplyExact(width,height),4))
                throw new IllegalArgumentException("Incomplete RGBA level");
            this.width=width; this.height=height; this.pixels=new byte[pixels.remaining()]; pixels.duplicate().get(this.pixels);
        }
        public int width() { return width; }
        public int height() { return height; }
        public ByteBuffer pixels() { return ByteBuffer.wrap(pixels).asReadOnlyBuffer(); }
    }

    public record Texture(SceneInputs.Key key,long revision,Encoding encoding,Sampling sampling,List<Level> levels,boolean generateMipmaps) implements Resource {
        public Texture(SceneInputs.Key key,long revision,Encoding encoding,Address address,List<Level> levels) {
            this(key,revision,encoding,Sampling.linear(address),levels,levels.size()==1);
        }
        public Texture(SceneInputs.Key key,long revision,Encoding encoding,Address address,List<Level> levels,boolean generateMipmaps) {
            this(key,revision,encoding,Sampling.linear(address),levels,generateMipmaps);
        }
        public Texture(SceneInputs.Key key,long revision,Encoding encoding,Sampling sampling,List<Level> levels) {
            this(key,revision,encoding,sampling,levels,levels.size()==1);
        }
        public Texture {
            Objects.requireNonNull(key); Objects.requireNonNull(encoding); Objects.requireNonNull(sampling); levels=List.copyOf(levels);
            if (revision<=0 || levels.isEmpty()) throw new IllegalArgumentException("Invalid texture revision");
            int width=levels.getFirst().width(),height=levels.getFirst().height();
            for (int i=0;i<levels.size();i++) {
                Level level=levels.get(i);
                if (level.width()!=width || level.height()!=height || i>0 && levels.get(i-1).width()==1 && levels.get(i-1).height()==1)
                    throw new IllegalArgumentException("Invalid mip chain");
                width=Math.max(1,width/2); height=Math.max(1,height/2);
            }
        }
    }
}
