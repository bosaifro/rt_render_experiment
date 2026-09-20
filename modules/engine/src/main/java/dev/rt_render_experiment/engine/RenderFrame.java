package dev.rt_render_experiment.engine;

import java.util.Objects;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.engine.abi.R2Abi;


public final class RenderFrame {
    private final int width, height, sample;
    private final SceneInputs.Origin origin;
    private final float[] inverse, current, previous;
    private final Environment environment;
    private final MediumInputs.Origin initialMedia;
    private final InitialMedia.ClipBoundaries clippedBoundaries;
    public enum View {
        WORLD(R2Abi.VIEW_WORLD,dev.rt_render_experiment.contract.Participation.CAMERA_PATH),
        VIEWMODEL(R2Abi.VIEW_VIEWMODEL,dev.rt_render_experiment.contract.Participation.VIEWMODEL);
        private final int identity,primaryMask;
        View(int identity,int primaryMask) { this.identity=identity;this.primaryMask=primaryMask; }
        public int identity() { return identity; }
        public int primaryMask() { return primaryMask; }
    }
    private final View view;

    public enum DepthConvention {
        FORWARD(0,1), REVERSED(1,0);
        final float near,far;
        DepthConvention(float near,float far) { this.near=near; this.far=far; }
    }
    private final DepthConvention depthConvention;
    public enum Reconstruction { RAW_DIAGNOSTIC, PORTABLE }
    public DepthConvention depthConvention() { return depthConvention; }
    public record Temporal(SceneInputs.Origin previousOrigin,boolean valid,float jitterX,float jitterY,float previousJitterX,float previousJitterY) {
        public Temporal {
            Objects.requireNonNull(previousOrigin);
            for(float value:new float[]{jitterX,jitterY,previousJitterX,previousJitterY}) if(!Float.isFinite(value))throw new IllegalArgumentException("Invalid jitter");
        }
    }
    private final Temporal temporal;
    private final Reconstruction reconstruction;
    private final boolean worldLightReuse;
    public record Presentation(boolean enabled,boolean rawClamp,float deltaSeconds) {
        public Presentation { if(!Float.isFinite(deltaSeconds)||deltaSeconds<0)throw new IllegalArgumentException("Invalid frame interval"); }
    }
    private final Presentation presentation;

    public static final class CameraMatrices {
        private final float[] view,projection;
        public CameraMatrices(float[] view,float[] projection) { this.view=matrix(view); this.projection=matrix(projection); }
        private float[] nativeColumns() {
            float[] result=new float[32];
            for(int row=0;row<4;row++)for(int column=0;column<4;column++) { result[column*4+row]=view[row*4+column]; result[16+column*4+row]=projection[row*4+column]; }
            return result;
        }
    }
    private final CameraMatrices cameraMatrices;
    private final CameraProjection cameraProjection;
    private final int outputWidth,outputHeight;
    public record Cloud(SceneInputs.Key resource,int width,int height,float baseAltitude,float thickness,float cellSize) {
        public Cloud {
            Objects.requireNonNull(resource);
            if (width<=0 || height<=0 || !Float.isFinite(baseAltitude) || !Float.isFinite(thickness) || !Float.isFinite(cellSize)
                || thickness<=0 || cellSize<=0) throw new IllegalArgumentException("Invalid cloud geometry");
        }
        public static Cloud absent() { return new Cloud(new SceneInputs.Key(0,0),1,1,192,4,12); }
    }
    public record Environment(SceneInputs.Vec3 sun, SceneInputs.Vec3 moon, float dayFraction, float rain,
                              float cloudDisplacement, boolean cloudsVisible, float referenceAltitude,
                              float sceneRadius, boolean cameraInWater,Cloud cloud) {
        public Environment(SceneInputs.Vec3 sun,SceneInputs.Vec3 moon,float dayFraction,float rain,float cloudDisplacement,
                           boolean cloudsVisible,float referenceAltitude,float sceneRadius,boolean cameraInWater) {
            this(sun,moon,dayFraction,rain,cloudDisplacement,cloudsVisible,referenceAltitude,sceneRadius,cameraInWater,Cloud.absent());
        }
        public Environment {
            Objects.requireNonNull(sun); Objects.requireNonNull(moon); Objects.requireNonNull(cloud);
            for (float value : new float[]{dayFraction,rain,cloudDisplacement,referenceAltitude,sceneRadius})
                if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid environment");
            if (dayFraction < 0 || dayFraction >= 1 || rain < 0 || rain > 1 || sceneRadius <= 0
                || Math.abs(lengthSquared(sun)-1)>1e-4f || Math.abs(lengthSquared(moon)-1)>1e-4f)
                throw new IllegalArgumentException("Invalid environment domain");
        }
        private static float lengthSquared(SceneInputs.Vec3 v) { return v.x()*v.x()+v.y()*v.y()+v.z()*v.z(); }

        public Environment withCameraInWater(boolean value) {
            return value==cameraInWater?this:new Environment(sun,moon,dayFraction,rain,cloudDisplacement,cloudsVisible,referenceAltitude,sceneRadius,value,cloud);
        }
        public boolean sameScene(Environment other) { return withCameraInWater(other.cameraInWater).equals(other); }
    }
    public RenderFrame(int width, int height, int sample, SceneInputs.Origin origin, float[] inverse,
                       float[] current, float[] previous, Environment environment, DepthConvention depthConvention) {
        this(width,height,sample,origin,inverse,current,previous,environment,new Temporal(origin,false,0,0,0,0),Reconstruction.RAW_DIAGNOSTIC,new Presentation(false,false,1f/60),null,width,height,depthConvention,View.WORLD,null,false,null,null);
    }
    private RenderFrame(int width,int height,int sample,SceneInputs.Origin origin,float[] inverse,float[] current,float[] previous,
                        Environment environment,Temporal temporal,Reconstruction reconstruction,Presentation presentation,CameraMatrices cameraMatrices,int outputWidth,int outputHeight,DepthConvention depthConvention,View view,CameraProjection cameraProjection,boolean worldLightReuse,MediumInputs.Origin initialMedia,InitialMedia.ClipBoundaries clippedBoundaries) {
        if (width <= 0 || height <= 0 || sample < 0) throw new IllegalArgumentException("Invalid render extent/sample");
        Math.multiplyExact(width,height);
        this.width=width; this.height=height; this.sample=sample; this.origin=Objects.requireNonNull(origin);
        this.inverse=matrix(inverse); this.current=matrix(current); this.previous=matrix(previous);
        this.initialMedia=initialMedia;this.clippedBoundaries=clippedBoundaries;
        if(clippedBoundaries!=null && initialMedia==null)throw new IllegalArgumentException("Invalid clipped medium classification");
        this.environment=initialMedia==null?Objects.requireNonNull(environment):environment.withCameraInWater(initialMedia.inWater());
        this.temporal=Objects.requireNonNull(temporal); this.reconstruction=Objects.requireNonNull(reconstruction);
        this.presentation=Objects.requireNonNull(presentation);this.worldLightReuse=worldLightReuse;
        this.cameraMatrices=cameraMatrices; this.depthConvention=Objects.requireNonNull(depthConvention);this.view=Objects.requireNonNull(view);
        if(view==View.VIEWMODEL && (width!=outputWidth || height!=outputHeight))
            throw new IllegalArgumentException("The viewmodel requires its full-resolution raw or portable reconstruction domain");
        this.cameraProjection=cameraProjection==null?new CameraProjection(this.inverse,this.current,depthConvention):cameraProjection;
        if(outputWidth<width || outputHeight<height)throw new IllegalArgumentException("Output extent is smaller than the rendering extent");
        Math.multiplyExact(outputWidth,outputHeight); this.outputWidth=outputWidth; this.outputHeight=outputHeight;
    }
    private static float[] matrix(float[] values) {
        if (values.length!=16) throw new IllegalArgumentException("Expected four matrix rows");
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite matrix");
        return values.clone();
    }

    public static RenderFrame fromCamera(int width,int height,int sample,SceneInputs.Origin origin,float[] view,float[] projection,
                                         Environment environment,DepthConvention depth) {
        var camera=new CameraMatrices(view,projection);
        var vp=matrixObject(camera.projection).mul(matrixObject(camera.view));
        var current=rows(vp);var inverse=rows(new org.joml.Matrix4f(vp).invert());
        return new RenderFrame(width,height,sample,origin,inverse,current,current,environment,depth).withCameraMatrices(camera)
            .withTemporal(new Temporal(origin,true,0,0,0,0));
    }
    private static org.joml.Matrix4f matrixObject(float[] rows) {
        var result=new org.joml.Matrix4f();
        for(int row=0;row<4;row++)for(int column=0;column<4;column++)result.set(column,row,rows[row*4+column]);return result;
    }
    private static float[] rows(org.joml.Matrix4f matrix) {
        var result=new float[16];
        for(int row=0;row<4;row++)for(int column=0;column<4;column++)result[row*4+column]=matrix.get(column,row);return result;
    }

    public RenderFrame withProjectionJitter(boolean enabled) {
        var jitter=dev.rt_render_experiment.vulkan.FrameUniformModel.ProjectionJitter.forFrame(
            new dev.rt_render_experiment.vulkan.FrameUniformModel.FrameSequence((long)sample+1),width,height,enabled);
        return withTemporal(new Temporal(temporal.previousOrigin(),temporal.valid(),jitter.ndcX(),jitter.ndcY(),temporal.previousJitterX(),temporal.previousJitterY()));
    }
    public CameraProjection camera() { return cameraProjection; }

    float[] inverseViewProjection() { return inverse.clone(); }
    float[] viewProjection() { return current.clone(); }
    float[] previousViewProjection() { return previous.clone(); }
    Temporal temporal() { return temporal; }

    public SceneInputs.Vec2 projectionJitter() { return new SceneInputs.Vec2(temporal.jitterX(),temporal.jitterY()); }

    public SceneInputs.Origin eye() { var offset=cameraProjection.eyeOffset();return new SceneInputs.Origin(origin.x()+offset.x(),origin.y()+offset.y(),origin.z()+offset.z()); }
    public View view() { return view; }
    public RenderFrame withView(View value) { return new RenderFrame(width,height,sample,origin,inverse,current,previous,environment,temporal,reconstruction,presentation,cameraMatrices,outputWidth,outputHeight,depthConvention,value,cameraProjection,worldLightReuse,initialMedia,clippedBoundaries); }
    public int width() { return width; }
    public int sample() { return sample; }
    public int height() { return height; }
    public int outputWidth() { return outputWidth; }
    public int outputHeight() { return outputHeight; }
    public SceneInputs.Origin origin() { return origin; }
    public Environment environment() { return environment; }
    public java.util.Optional<MediumInputs.Origin> initialMedia() { return java.util.Optional.ofNullable(initialMedia); }
    public RenderFrame withInitialMedia(MediumInputs.Origin value) {
        Objects.requireNonNull(value);
        return new RenderFrame(width,height,sample,origin,inverse,current,previous,environment,temporal,reconstruction,presentation,cameraMatrices,
            outputWidth,outputHeight,depthConvention,view,cameraProjection,worldLightReuse,value,null);
    }

    RenderFrame withClassifiedMedia(MediumInputs.Origin value,InitialMedia.ClipBoundaries boundaries) {
        return new RenderFrame(width,height,sample,origin,inverse,current,previous,environment,temporal,reconstruction,presentation,cameraMatrices,
            outputWidth,outputHeight,depthConvention,view,cameraProjection,worldLightReuse,Objects.requireNonNull(value),Objects.requireNonNull(boundaries));
    }
    int clippedMediumPrimitives() { return clippedBoundaries==null?0:clippedBoundaries.primitives(); }
    InitialMedia.ClipBoundaries clippedBoundaries() { return clippedBoundaries; }
    public Reconstruction reconstruction() { return reconstruction; }
    public boolean worldLightReuse() { return worldLightReuse; }
    public RenderFrame withWorldLightReuse(boolean enabled) { return new RenderFrame(width,height,sample,origin,inverse,current,previous,environment,temporal,reconstruction,presentation,cameraMatrices,outputWidth,outputHeight,depthConvention,view,cameraProjection,enabled,initialMedia,clippedBoundaries); }
    public Presentation presentation() { return presentation; }
    public RenderFrame withEnvironment(Environment replacement) { return new RenderFrame(width,height,sample,origin,inverse,current,previous,replacement,temporal,reconstruction,presentation,cameraMatrices,outputWidth,outputHeight,depthConvention,view,cameraProjection,worldLightReuse,initialMedia,clippedBoundaries); }
    public RenderFrame withTemporal(Temporal replacement) { return new RenderFrame(width,height,sample,origin,inverse,current,previous,environment,replacement,reconstruction,presentation,cameraMatrices,outputWidth,outputHeight,depthConvention,view,cameraProjection,worldLightReuse,initialMedia,clippedBoundaries); }
    public RenderFrame withReconstruction(Reconstruction mode) { return new RenderFrame(width,height,sample,origin,inverse,current,previous,environment,temporal,mode,presentation,cameraMatrices,outputWidth,outputHeight,depthConvention,view,cameraProjection,worldLightReuse,initialMedia,clippedBoundaries); }
    public RenderFrame withPresentation(Presentation value) { return new RenderFrame(width,height,sample,origin,inverse,current,previous,environment,temporal,reconstruction,value,cameraMatrices,outputWidth,outputHeight,depthConvention,view,cameraProjection,worldLightReuse,initialMedia,clippedBoundaries); }
    public RenderFrame withCameraMatrices(CameraMatrices value) { return new RenderFrame(width,height,sample,origin,inverse,current,previous,environment,temporal,reconstruction,presentation,value,outputWidth,outputHeight,depthConvention,view,cameraProjection,worldLightReuse,initialMedia,clippedBoundaries); }

    public RenderFrame withLens(float[] projection) {
        var lens=matrix(projection);
        new CameraProjection(rows(matrixObject(lens).invert()),lens,depthConvention);
        if(lens[3]!=0 || lens[7]!=0 || lens[15]!=0 || lens[11]==0)throw new IllegalArgumentException("A reconstruction lens must be centered at its view origin");
        var effectiveView=matrixObject(lens).invert().mul(matrixObject(current));
        if(Math.abs(effectiveView.m03())>1e-4f || Math.abs(effectiveView.m13())>1e-4f || Math.abs(effectiveView.m23())>1e-4f || Math.abs(effectiveView.m33()-1)>1e-4f)
            throw new IllegalArgumentException("The declared reconstruction lens does not produce an affine view pose");
        return withCameraMatrices(new CameraMatrices(rows(effectiveView),lens));
    }
    public RenderFrame withOutputExtent(int width,int height) { return new RenderFrame(this.width,this.height,sample,origin,inverse,current,previous,environment,temporal,reconstruction,presentation,cameraMatrices,width,height,depthConvention,view,cameraProjection,worldLightReuse,initialMedia,clippedBoundaries); }
    public boolean previousValid() { return temporal.valid(); }

    RenderFrame withSubmittedPredecessor(RenderFrame before,boolean valid) {
        var predecessor=valid?before:this;
        var correspondence=new Temporal(predecessor.origin,valid,temporal.jitterX(),temporal.jitterY(),
            predecessor.temporal.jitterX(),predecessor.temporal.jitterY());
        return new RenderFrame(width,height,sample,origin,inverse,current,predecessor.current,environment,correspondence,
            reconstruction,presentation,cameraMatrices,outputWidth,outputHeight,depthConvention,view,cameraProjection,worldLightReuse,initialMedia,clippedBoundaries);
    }
    boolean compatibleHistory(RenderFrame before) {
        return view==before.view && width==before.width && height==before.height && outputWidth==before.outputWidth && outputHeight==before.outputHeight
            && reconstruction==before.reconstruction && worldLightReuse==before.worldLightReuse && depthConvention==before.depthConvention
            && environment.cameraInWater()==before.environment.cameraInWater()
            && (initialMedia==null?before.initialMedia==null:before.initialMedia!=null && initialMedia.enclosures().equals(before.initialMedia.enclosures()));
    }
}
