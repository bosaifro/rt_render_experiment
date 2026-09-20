package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;


final class LightPreparationFixtures {
    private LightPreparationFixtures() {}
    static final SceneInputs.Key AREA_KEY=new SceneInputs.Key(20,1),AREA_PART=new SceneInputs.Key(20,2),TEXTURE=new SceneInputs.Key(20,3);
    record Input(List<SceneCompiler.Compiled> geometry,List<LightInputs.Source> lights,List<TextureInputs.Texture> textures,SceneInputs.Origin origin) {
        SceneStore.Revision revision() { return new SceneStore.Revision(1,1,geometry,geometry.stream().mapToLong(SceneCompiler.Compiled::bytes).sum(),textures,lights); }
    }
    static Input at(int step) {
        var base=SceneFixtures.prepare(false).geometry().getFirst();var source=base.input();var primitive=source.primitives().getFirst();
        var neutral=new SceneInputs.Key(0,0);
        var layers=new MaterialInputs.Layers(TEXTURE,true,neutral,MaterialInputs.Emission.CALIBRATED,new SceneInputs.Vec3(1.5f,.25f,.1f),true,false,MaterialInputs.Sampling.CRISP);
        var surface=new SceneInputs.Surface(AREA_PART,1,0,TEXTURE,TEXTURE,SceneInputs.Coverage.OPAQUE,.5f,true,0,0,0,layers,SceneInputs.HostOcclusion.BLOCK);
        var corners=new ArrayList<>(primitive.corners());
        if(step==7)corners.set(1,corners.getFirst());
        if(step==8)for(int i=1;i<corners.size();i++)corners.set(i,corners.getFirst());
        var primitives=new ArrayList<SceneInputs.Primitive>();
        if(step==11)primitives.add(new SceneInputs.Primitive(new SceneInputs.Key(20,99),0,primitive.surface(),primitive.corners()));
        primitives.add(new SceneInputs.Primitive(AREA_PART,0,surface,corners));
        var transform=SceneInputs.Transform.identity().rows();transform[0]=step==6?-.45f:.45f;transform[5]=.45f;
        transform[3]=.75f+(step==4?.125f:0);transform[11]=.75f;
        var emitter=new SceneCompiler().compile(new SceneInputs.Geometry(AREA_KEY,source.revision(),source.origin(),new SceneInputs.Transform(transform),source.previous(),true,
            SceneInputs.Motion.RIGID,source.participation(),primitives));
        var geometry=new ArrayList<SceneCompiler.Compiled>();
        if(step==5)geometry.add(new SceneCompiler().compile(new SceneInputs.Geometry(new SceneInputs.Key(1,1),source.revision(),new SceneInputs.Origin(100,64,0),
            source.current(),source.previous(),true,source.motion(),source.participation(),source.primitives())));
        geometry.add(base);geometry.add(emitter);
        var lights=new ArrayList<LightInputs.Source>();
        var world=new SceneInputs.Origin(-.1,64,-1);
        lights.add(new LightInputs.Point(new SceneInputs.Key(900,1),1,new LightInputs.Emission(0,1,new SceneInputs.Vec3(0,0,0)),world,0,LightInputs.Stratum.WORLD,world,.12f));
        for(int hand=0;hand<2;hand++) {
            var p=new SceneInputs.Origin((hand==0?-.28:.28)+(step==2 && hand==0?32:0),63.65,-.45);
            lights.add(new LightInputs.Point(new SceneInputs.Key(902,hand+1),1,new LightInputs.Emission(hand==0?0:10,1,new SceneInputs.Vec3(0,0,0)),p,0,
                LightInputs.Stratum.CAMERA_ATTACHED,p,.12f));
        }
        lights.add(new LightInputs.Area(new SceneInputs.Key(901,1),1,new LightInputs.Emission(0,step==3?2:1,new SceneInputs.Vec3(1,.2f,.1f)),source.origin(),0,
            LightInputs.Stratum.OBJECT,AREA_KEY,AREA_PART,0));
        var texture=new TextureInputs.Texture(TEXTURE,step==9?2:1,TextureInputs.Encoding.SRGB,TextureInputs.Address.CLAMP,
            List.of(new TextureInputs.Level(1,1,ByteBuffer.wrap(new byte[]{(byte)(step==9?64:255),96,48,-1}))));
        return new Input(List.copyOf(geometry),List.copyOf(lights),List.of(texture),step==10?new SceneInputs.Origin(-16.0000001,64,0):source.origin());
    }
    static SceneCompiler.Compiled participation(SceneCompiler.Compiled mesh,Participation participation) {
        var source=mesh.input();return new SceneCompiler().compile(new SceneInputs.Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),false,source.motion(),participation,source.primitives()));
    }}
