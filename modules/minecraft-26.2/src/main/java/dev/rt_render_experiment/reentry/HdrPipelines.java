package dev.rt_render_experiment.reentry;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;


public final class HdrPipelines {
    public static final String LINEAR="RT_RENDER_EXPERIMENT_HDR_LINEAR";
    private static final Map<RenderPipeline,RenderPipeline> VARIANTS=new IdentityHashMap<>();
    private HdrPipelines() {}
    public static RenderPipeline adapt(RenderPipeline pipeline,GpuFormat[] formats) {
        if(RtRenderExperimentFrameGraph.active()==null || formats==null || formats.length!=1 || formats[0]!=GpuFormat.RGBA16_FLOAT)return pipeline;
        var targets=pipeline.getColorTargetStates();
        if(targets==null || targets.length!=1 || targets[0]==null || targets[0].format()==GpuFormat.RGBA16_FLOAT)return pipeline;
        if(targets[0].format()!=GpuFormat.RGBA8_UNORM)throw new IllegalArgumentException("Unqualified native HDR target format");
        return VARIANTS.computeIfAbsent(pipeline,HdrPipelines::create);
    }
    private static RenderPipeline create(RenderPipeline pipeline) {
        var target=pipeline.getColorTargetStates()[0];
        var snippet=new RenderPipeline.Snippet(Optional.of(pipeline.getVertexShader()),Optional.of(pipeline.getFragmentShader()),Optional.of(pipeline.getShaderDefines()),
            Optional.of(pipeline.getBindGroupLayouts()),new ColorTargetState[]{new ColorTargetState(target.blendFunction(),GpuFormat.RGBA16_FLOAT,target.writeMask())},1,
            Optional.ofNullable(pipeline.getDepthStencilState()),Optional.of(pipeline.getPolygonMode()),Optional.of(pipeline.isCull()),
            Arrays.copyOf(pipeline.getVertexFormatBindings(),pipeline.getVertexFormatBindings().length),Optional.of(pipeline.getPrimitiveTopology()));
        var builder=RenderPipeline.builder(snippet).withLocation(pipeline.getLocation().withSuffix("_rt_render_experiment_hdr"));
        if(!pipeline.getFragmentShader().getPath().startsWith("post/"))builder.withShaderDefine(LINEAR);
        return builder.build();
    }
    public static String linearize(String source) {
        if(!java.util.regex.Pattern.compile("\\bout\\s+vec4\\s+fragColor\\s*;").matcher(source).find())
            throw new IllegalArgumentException("Native residual shader has no qualified color output");
        var entry=java.util.regex.Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)").matcher(source);
        if(!entry.find())throw new IllegalArgumentException("Native residual shader has no main entry");
        return entry.replaceFirst("void rt_render_experiment_native_main()")+"\nvoid main() { rt_render_experiment_native_main(); vec3 c=max(fragColor.rgb,vec3(0.0)); fragColor.rgb=mix(c/12.92,pow((c+0.055)/1.055,vec3(2.4)),greaterThan(c,vec3(0.04045))); }\n";
    }
    public static boolean hasColorOutput(String source) {
        return java.util.regex.Pattern.compile("\\bout\\s+vec4\\s+fragColor\\s*;").matcher(source).find();
    }
}
