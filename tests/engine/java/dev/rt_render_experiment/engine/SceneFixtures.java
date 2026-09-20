package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.FluidInputs;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;
import dev.rt_render_experiment.contract.LightInputs;
import org.joml.Matrix4f;


public final class SceneFixtures {
    private SceneFixtures() {}
    private static Path cohort(Path root,String name) throws java.io.IOException { return root==null?null:java.nio.file.Files.createDirectory(root.resolve(name)); }
    private static void bufferReadbackOrdering(FixtureHost host) {
        try(var source=host.resources.allocateDevice(16,org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT|org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            var first=new dev.rt_render_experiment.vulkan.VulkanBufferReadback(host.resources,source);
            var second=new dev.rt_render_experiment.vulkan.VulkanBufferReadback(host.resources,source)) {
            var command=host.begin();source.markUsed();
            org.lwjgl.vulkan.VK12.vkCmdFillBuffer(command,source.view().handle(),source.view().offset(),source.view().length(),0x11223344);
            first.record(command);first.acceptRecording();
            org.lwjgl.vulkan.VK12.vkCmdFillBuffer(command,source.view().handle(),source.view().offset(),source.view().length(),0x55667788);
            second.record(command);second.acceptRecording();
            long submitted=host.submit(command);host.complete(command,submitted);
            try(var a=first.readback();var b=second.readback()) {
                for(int word=0;word<4;word++)require(a.data().getInt(word*4)==0x11223344 && b.data().getInt(word*4)==0x55667788,
                    "Mid-frame readback lost ordering against a later source overwrite");
            }
        }
        host.assertValidation();
        System.out.println("Buffer readback ordering PASS: distinct copies before/after a source overwrite, observed completion and synchronization validation");
    }
    private record Result(ByteBuffer hits,ByteBuffer signals,ByteBuffer radiance,int built,int reused,long uploadBytes,ByteBuffer history,ByteBuffer display,ByteBuffer exposure,long textureBytes,long reusedRecordBytes,long reusedSourceBytes) {}
    static RenderFrame frame() {
        var projection=new Matrix4f().perspective((float)Math.PI/2,1,0.1f,100,true);
        var environment=new RenderFrame.Environment(new SceneInputs.Vec3(0,1,0),new SceneInputs.Vec3(0,-1,0),0.25f,0,0,false,64,128,false);
        return new RenderFrame(16,16,0,new SceneInputs.Origin(0,64,0),rows(new Matrix4f(projection).invert()),rows(projection),rows(projection),environment,RenderFrame.DepthConvention.FORWARD);
    }
    private static float[] rows(Matrix4f m) {
        return new float[]{m.m00(),m.m10(),m.m20(),m.m30(),m.m01(),m.m11(),m.m21(),m.m31(),m.m02(),m.m12(),m.m22(),m.m32(),m.m03(),m.m13(),m.m23(),m.m33()};
    }
    static SceneStore.Revision depthScene(int material,SceneInputs.HostOcclusion policy,boolean background) {
        var source=prepare(false).geometry().getFirst().input();var primitive=source.primitives().getFirst();var s=primitive.surface();
        var front=new SceneInputs.Surface(s.key(),material,s.properties(),s.colorResource(),s.emissionResource(),
            material==1?SceneInputs.Coverage.OPAQUE:SceneInputs.Coverage.DIELECTRIC,s.cutoff(),true,material==8?1:0,0,0,MaterialInputs.Layers.plain(s.colorResource()),policy);
        var primitives=new java.util.ArrayList<SceneInputs.Primitive>();
        primitives.add(new SceneInputs.Primitive(primitive.part(),0,front,primitive.corners()));
        if(background) {
            var corners=primitive.corners().stream().map(c->new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x()*2,c.position().y()*2,-6),
                c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList();
            primitives.add(new SceneInputs.Primitive(primitive.part(),1,s,corners));
        }
        var input=new SceneInputs.Geometry(source.key(),new SceneInputs.Revision(1,2,0,0,policy.ordinal()+1,1,1),source.origin(),source.current(),source.previous(),false,
            source.motion(),source.participation(),primitives);
        var compiled=new SceneCompiler().compile(input);
        return new SceneStore.Revision(4,1,List.of(compiled),compiled.bytes());
    }
    private static SceneStore.Revision weatherGeometry(boolean upward,int properties) {
        var base=prepare(false).geometry().getFirst().input();var p=base.primitives().getFirst();var s=p.surface();
        var surface=new SceneInputs.Surface(s.key(),s.material(),properties,s.colorResource(),s.emissionResource(),s.coverage(),s.cutoff(),s.doubleSided(),s.medium(),s.layer(),s.layerSeparation(),s.layers(),s.hostOcclusion());
        var corners=p.corners();
        if(upward) {
            float[][] positions={{-2,-2,-5},{-2,-2,-1},{2,-2,-1},{2,-2,-5}};var changed=new java.util.ArrayList<SceneInputs.Corner>();
            for(int i=0;i<4;i++) { var c=corners.get(i);changed.add(new SceneInputs.Corner(new SceneInputs.Vec3(positions[i][0],positions[i][1],positions[i][2]),c.u(),c.v(),c.tint(),new SceneInputs.Vec3(0,1,0),new SceneInputs.Vec3(1,0,0))); }
            corners=changed;
        }
        var geometry=new SceneInputs.Geometry(base.key(),base.revision(),base.origin(),base.current(),base.previous(),base.previousValid(),base.motion(),base.participation(),List.of(new SceneInputs.Primitive(p.part(),p.ordinal(),surface,corners)));
        var compiled=new SceneCompiler().compile(geometry);return new SceneStore.Revision(1,1,List.of(compiled),compiled.bytes());
    }
    private static void clearTexture(FixtureHost host,org.lwjgl.vulkan.VkCommandBuffer command,dev.rt_render_experiment.vulkan.VulkanResources.Image image,int[][] rgba) {
        image.markUsed();
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()) {
            dev.rt_render_experiment.vulkan.VulkanBarriers.record(command,stack,dev.rt_render_experiment.vulkan.VulkanBarriers.GRAPHICS_BOUNDARY);
            for(int mip=0;mip<rgba.length;mip++) {
                var color=org.lwjgl.vulkan.VkClearColorValue.calloc(stack);
                for(int channel=0;channel<4;channel++)color.float32(channel,rgba[mip][channel]/255f);
                var range=org.lwjgl.vulkan.VkImageSubresourceRange.calloc(stack).aspectMask(org.lwjgl.vulkan.VK12.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(mip).levelCount(1).layerCount(1);
                org.lwjgl.vulkan.VK12.vkCmdClearColorImage(command,image.view().image(),org.lwjgl.vulkan.VK12.VK_IMAGE_LAYOUT_GENERAL,color,range);
            }
        }
    }
    static final SceneInputs.Key FILTER_TEXTURE=new SceneInputs.Key(101,1),BLACK_TEXTURE=new SceneInputs.Key(101,2);
    static SceneStore.Revision filterScene(boolean filters,boolean mirror,boolean front) {
        var source=prepare(false).geometry().getFirst().input();var primitive=source.primitives().getFirst();var s=primitive.surface();
        var base=new SceneInputs.Surface(s.key(),mirror?7:1,0,s.colorResource(),s.emissionResource(),s.coverage(),s.cutoff(),s.doubleSided(),0,0,0,s.layers(),s.hostOcclusion());
        var geometry=new java.util.ArrayList<SceneCompiler.Compiled>();var compiler=new SceneCompiler();
        geometry.add(compiler.compile(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),false,
            source.motion(),source.participation(),List.of(new SceneInputs.Primitive(primitive.part(),0,base,primitive.corners())))));
        if(mirror) {
            var layers=new MaterialInputs.Layers(BLACK_TEXTURE,true,new SceneInputs.Key(0,0),MaterialInputs.Emission.CALIBRATED,new SceneInputs.Vec3(1.5f,1.5f,1.5f),
                true,false,MaterialInputs.Sampling.TEXTURE_SAMPLER);
            var emitter=new SceneInputs.Surface(new SceneInputs.Key(20,1),66,0,BLACK_TEXTURE,new SceneInputs.Key(0,0),SceneInputs.Coverage.OPAQUE,0,true,0,0,0,layers,SceneInputs.HostOcclusion.PASS);
            geometry.add(compiler.compile(plane(new SceneInputs.Key(20,1),0,emitter,Participation.TRANSPORT,true)));
        }
        if(filters)for(int index=0;index<(mirror?7:6);index++) {
            float z=index==6?1:index==2 || index==3?-1.9f:-2.7f+index*0.4f;
            var surface=new SceneInputs.Surface(new SceneInputs.Key(40,index),1,0,FILTER_TEXTURE,new SceneInputs.Key(0,0),SceneInputs.Coverage.FILTER,0,true,0,0,0,
                MaterialInputs.Layers.plain(FILTER_TEXTURE),SceneInputs.HostOcclusion.PASS);
            geometry.add(compiler.compile(plane(new SceneInputs.Key(40,index),z,surface,mirror?Participation.TRANSPORT:Participation.SHADOW,front)));
        }
            return new SceneStore.Revision(50,1,geometry,geometry.stream().mapToLong(SceneCompiler.Compiled::bytes).sum());
    }
    private static SceneInputs.Geometry plane(SceneInputs.Key key,float z,SceneInputs.Surface surface,int mask,boolean front) {
        float[][] points={{-100,-100,z},{-100,100,z},{100,100,z},{100,-100,z}};
        var corners=new java.util.ArrayList<SceneInputs.Corner>();
        for(int i=0;i<4;i++) { float[] p=points[front?i:3-i];corners.add(new SceneInputs.Corner(new SceneInputs.Vec3(p[0],p[1],p[2]),0.5f,0.5f,
            new SceneInputs.Color(1,1,1,1),new SceneInputs.Vec3(0,0,front?-1:1),new SceneInputs.Vec3(1,0,0))); }
        return new SceneInputs.Geometry(key,new SceneInputs.Revision(1,1,0,0,1,1,1),frame().origin(),SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),false,
            SceneInputs.Motion.STATIC,new Participation(mask,false),List.of(new SceneInputs.Primitive(key,0,surface,corners)));
    }
    static SceneStore.Revision prepare(boolean alternate) {
        return prepare(alternate,SceneInputs.Coverage.OPAQUE,new SceneInputs.Key(0,0));
    }
    private static SceneStore.Revision pose(SceneInputs.Motion motion,float displacement,long topology) {
        var source=prepare(false).geometry().getFirst().input(); var primitive=source.primitives().getFirst();
        var corners=primitive.corners();
        if(motion==SceneInputs.Motion.DEFORMING) corners=corners.stream().map(c->new SceneInputs.Corner(
            new SceneInputs.Vec3(c.position().x()+displacement,c.position().y(),c.position().z()),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList();
        float[] rows=SceneInputs.Transform.identity().rows(); if(motion==SceneInputs.Motion.RIGID)rows[3]=displacement;
        float[] misleadingPrevious=SceneInputs.Transform.identity().rows(); misleadingPrevious[3]=100;
        var input=new SceneInputs.Geometry(source.key(),new SceneInputs.Revision(1,topology,2,2,1,1,1),source.origin(),
            new SceneInputs.Transform(rows),new SceneInputs.Transform(misleadingPrevious),true,motion,source.participation(),
            List.of(new SceneInputs.Primitive(primitive.part(),primitive.ordinal(),primitive.surface(),corners)));
        var compiled=new SceneCompiler().compile(input); return new SceneStore.Revision(3,1,List.of(compiled),compiled.bytes());
    }
    static SceneStore.Revision prepare(boolean alternate,SceneInputs.Coverage coverage,SceneInputs.Key colorResource) {
        var key=new SceneInputs.Key(5,11); var revision=new SceneInputs.Revision(1,1,0,0,1,1,1);

        String sourceId=alternate?"surface/9001":"fixture/quad";
        require(Set.of("surface/9001","fixture/quad").contains(sourceId),"Unknown synthetic producer");
        float[][] positions={{-1,-1,-3},{1,-1,-3},{1,1,-3},{-1,1,-3}};
        SceneInputs.Corner[] corners=new SceneInputs.Corner[4];
        for (int event=0;event<4;event++) {
            int i=alternate?3-event:event;
            float[] p=positions[i];
            if (alternate) {
                var encoded=ByteBuffer.allocate(6).order(java.nio.ByteOrder.BIG_ENDIAN);
                for (float value:p) encoded.putShort((short)(value*4096));
                encoded.flip(); p=new float[]{encoded.getShort()/4096f,encoded.getShort()/4096f,encoded.getShort()/4096f};
            }
            corners[i]=new SceneInputs.Corner(new SceneInputs.Vec3(p[0],p[1],p[2]),(i==1||i==2)?1:0,i>=2?1:0,
                new SceneInputs.Color(0.5f,0.25f,0.125f,1),new SceneInputs.Vec3(0,0,1),new SceneInputs.Vec3(1,0,0));
        }
        var surface=new SceneInputs.Surface(new SceneInputs.Key(8,1),1,0,colorResource,new SceneInputs.Key(0,0),
            coverage,0.5f,true,0,0,0,MaterialInputs.Layers.plain(colorResource),SceneInputs.HostOcclusion.BLOCK);
        var geometry=new SceneInputs.Geometry(key,revision,new SceneInputs.Origin(0,64,0),SceneInputs.Transform.identity(),SceneInputs.Transform.identity(),true,
            SceneInputs.Motion.STATIC,new Participation(Participation.WORLD,true),List.of(new SceneInputs.Primitive(key,0,surface,List.of(corners))));


        var compiled=new SceneCompiler().compile(geometry);
        return new SceneStore.Revision(2,1,List.of(compiled),compiled.bytes());
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }}
