package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import dev.rt_render_experiment.contract.*;
import org.lwjgl.vulkan.VK12;


public final class FluidContactFixtures {
    private FluidContactFixtures() {}
    private static final MediumInputs.Definition WATER=MediumProfiles.definition(MediumInputs.Kind.WATER,new SceneInputs.Vec3(1,1,1));
    private static SceneStore.Revision variant(SceneStore.Revision source,boolean tiled,boolean missing) {
        if(!tiled)return source;var meshes=source.geometry().stream().map(mesh->{
            if(mesh.input().key().high()!=5600)return mesh;
            var primitives=new ArrayList<SceneInputs.Primitive>();
            for(var primitive:mesh.input().primitives())primitives.addAll(TiledContactTest.tiles(primitive,primitive.part().low()==1?2:0));
            if(missing)primitives.removeLast();return TiledContactTest.compile(mesh.input(),primitives);
        }).toList();return JoinedModelVolumesTest.revision(meshes);
    }
    static SceneStore.Revision scene(boolean column,boolean wall,boolean missing,SceneInputs.Origin origin) {
        var cells=column?Set.of(new FluidVolumeCompilerTest.Cell(0,0,-2),new FluidVolumeCompilerTest.Cell(0,1,-2)):Set.of(new FluidVolumeCompilerTest.Cell(0,0,-2));
        var original=FluidVolumeCompilerTest.source(cells,false).geometry().getFirst().input();
        var fluidCells=new ArrayList<FluidInputs.Cell>();
        var full=new FluidInputs.FaceCoverage(List.of(new FluidInputs.Rectangle(0,0,1,1)),0);
        for(var cell:original.fluids()) {
            var neighbors=new ArrayList<>(cell.neighbors());if(cell.position().y()==0) { neighbors.set(0,full);if(wall)neighbors.set(5,full); }
            fluidCells.add(new FluidInputs.Cell(cell.part(),cell.ordinal(),cell.position(),cell.neighborhood(),cell.candidateFaces(),cell.overlayFaces(),cell.flowX(),cell.flowZ(),cell.tint(),cell.still(),cell.flowing(),cell.overlay(),neighbors,cell.volume()));
        }
        var pose=SceneInputs.Transform.identity();var meshes=new ArrayList<SceneCompiler.Compiled>();var compiler=new SceneCompiler();
        meshes.add(compiler.compile(new SceneInputs.Geometry(original.key(),original.revision(),origin,pose,pose,false,original.motion(),original.participation(),List.of(),fluidCells)));
        var primitives=new ArrayList<SceneInputs.Primitive>();
        if(!missing)primitives.add(plane(1,new float[][]{{0,0,-2},{0,0,-1},{1,0,-1},{1,0,-2}}));
        if(wall)primitives.add(plane(2,new float[][]{{1,0,-2},{1,0,-1},{1,1,-1},{1,1,-2}}));
        if(!primitives.isEmpty())meshes.add(compiler.compile(new SceneInputs.Geometry(new SceneInputs.Key(5600,1),original.revision(),origin,pose,pose,false,original.motion(),original.participation(),primitives)));
        meshes.add(compiler.compile(new SceneInputs.Geometry(new SceneInputs.Key(5601,1),original.revision(),origin,pose,pose,false,original.motion(),original.participation(),
            List.of(plane(3,new float[][]{{-32,-2,-32},{-32,-2,32},{32,-2,32},{32,-2,-32}})))));
        return JoinedModelVolumesTest.revision(meshes);
    }
    private static SceneInputs.Primitive plane(int id,float[][] points) {
        var key=new SceneInputs.Key(5600,id);var neutral=new SceneInputs.Key(0,0);
        var layers=new MaterialInputs.Layers(neutral,true,neutral,MaterialInputs.Emission.CALIBRATED,new SceneInputs.Vec3(5,3,1),true,false,MaterialInputs.Sampling.CRISP);
        var surface=new SceneInputs.Surface(key,1,0,neutral,neutral,SceneInputs.Coverage.OPAQUE,0,true,0,0,0,layers,SceneInputs.HostOcclusion.BLOCK);
        var corners=new ArrayList<SceneInputs.Corner>();
        for(int i=0;i<4;i++)corners.add(new SceneInputs.Corner(new SceneInputs.Vec3(points[i][0],points[i][1],points[i][2]),i<2?0:1,i==1 || i==2?1:0,
            new SceneInputs.Color(1,1,1,1),new SceneInputs.Vec3(0,0,0),new SceneInputs.Vec3(0,0,0)));
        return new SceneInputs.Primitive(key,0,surface,corners);
    }
    private static RenderFrame frame(SceneInputs.Origin origin,int step) {
        float y=step==2 || step==5?.35f:step==3?.001f:step==8?-.5f:step==9?-.001f:1.4f;
        var view=step==5?new org.joml.Matrix4f().rotationY((float)Math.PI/2):new org.joml.Matrix4f().rotationX((step>=8?-1:1)*(float)Math.PI/2);
        view.translate(-.5f,-y,1.5f);var env=SceneFixtures.frame().environment();
        env=new RenderFrame.Environment(env.sun(),env.moon(),env.dayFraction(),0,0,false,-100000,env.sceneRadius(),false,env.cloud());
        return RenderFrame.fromCamera(16,16,0,origin,CameraFixtures.rows(view),CameraFixtures.rows(new org.joml.Matrix4f().perspective((float)Math.PI/6,1,.1f,100,true)),env,RenderFrame.DepthConvention.FORWARD)
            .withReconstruction(RenderFrame.Reconstruction.PORTABLE);
    }
    private static FrameScene content(long id,SceneStore.Revision source,boolean automatic,boolean missing,Map<SceneStore.Revision,SceneStore.Revision> cache) {
        var input=new FrameScene(id,source,null,List.of(),LightInputs.Publication.empty());if(automatic)return input.withPreparedVolumes();if(missing)return input;
        var revision=cache.computeIfAbsent(source,s->{
            var geometry=s.geometry().stream().map(mesh->{
                var water=new HashMap<SceneCompiler.Part,Long>();var contact=new HashMap<SceneCompiler.Part,Long>();
                for(var part:mesh.parts())if(part.surface().material()==8)water.put(part,1L);else if(mesh.input().key().high()==5600)contact.put(part,1L);
                return water.isEmpty() && contact.isEmpty()?mesh:new SceneCompiler().withVolumes(mesh,water,contact);
            }).toList();return JoinedModelVolumesTest.revision(geometry);
        });
        return new FrameScene(id,revision,null,List.of(),LightInputs.Publication.empty()).withMediumDomain(new MediumInputs.Domain(List.of(new MediumInputs.Volume(1,WATER))));
    }
    private static Path directory(Path parent,String name) throws Exception { if(parent==null)return null;var path=parent.resolve(name);Files.createDirectory(path);return path; }
    private static HostExecution.ImageGrant grant(ResourceViews.Image image,long serial) { return new HostExecution.ImageGrant(image,1,Set.of(HostExecution.Access.ATTACHMENT_WRITE),serial); }
    private static HostExecution.Window window(long serial,long id,HostExecution.Stage stage,HostExecution.ImageGrant... images) { return new HostExecution.Window(1,id,serial,stage,List.of(),List.of(images)); }}
