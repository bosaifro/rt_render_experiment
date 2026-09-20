package dev.rt_render_experiment.engine;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.contract.TextureInputs;


final class WorldLightDomain {
    private record Texture(SceneInputs.Key key,long revision,TextureInputs.Encoding encoding,TextureInputs.Sampling sampling) {}
    private final long world;
    private final List<SceneCompiler.Compiled> geometry;
    private final List<LightInputs.Source> sources;
    private final List<Texture> textures;
    private final dev.rt_render_experiment.contract.MediumInputs.Domain media;
    private final Object producerRevision;
    WorldLightDomain(long world,Object producerRevision) {
        this.world=world;this.producerRevision=java.util.Objects.requireNonNull(producerRevision);
        geometry=List.of();sources=List.of();textures=List.of();media=null;
    }
    WorldLightDomain(long world,List<SceneCompiler.Compiled> geometry,List<LightInputs.Source> lights,List<? extends TextureInputs.Resource> resources) {
        this(world,geometry,lights,resources,null);
    }
    WorldLightDomain(long world,List<SceneCompiler.Compiled> geometry,List<LightInputs.Source> lights,List<? extends TextureInputs.Resource> resources,dev.rt_render_experiment.contract.MediumInputs.Domain media) {
        this.world=world;
        producerRevision=null;
        this.media=media;
        sources=lights.stream().filter(s->s.stratum()==LightInputs.Stratum.WORLD).sorted(Comparator.comparing(LightInputs.Source::key)).toList();
        Set<SceneInputs.Key> emitterMeshes=new HashSet<>();
        for(var source:sources)if(source instanceof LightInputs.Area area)emitterMeshes.add(area.geometry());
        this.geometry=geometry.stream().filter(g->g.triangleCount()>0 && (g.input().motion()==SceneInputs.Motion.STATIC || emitterMeshes.contains(g.input().key())))
            .sorted(Comparator.comparing(g->g.input().key())).toList();
        Set<SceneInputs.Key> textureKeys=new HashSet<>();
        for(var mesh:this.geometry)if(emitterMeshes.contains(mesh.input().key()))for(var part:mesh.parts()) {
            var surface=part.surface();textureKeys.add(surface.colorResource());textureKeys.add(surface.emissionResource());
            textureKeys.add(surface.layers().coverage());textureKeys.add(surface.layers().overlay());
        }
        textures=resources.stream().filter(r->textureKeys.contains(r.key())).sorted(Comparator.comparing(TextureInputs.Resource::key))
            .map(r->new Texture(r.key(),r.revision(),r.encoding(),r.sampling())).toList();
    }
    boolean matches(WorldLightDomain other) {
        if(producerRevision!=null || other!=null && other.producerRevision!=null)
            return other!=null && world==other.world && java.util.Objects.equals(producerRevision,other.producerRevision);
        if(other==null || world!=other.world || !java.util.Objects.equals(media,other.media) || !sources.equals(other.sources) || !textures.equals(other.textures) || geometry.size()!=other.geometry.size())return false;
        for(int i=0;i<geometry.size();i++) {
            var a=geometry.get(i);var b=other.geometry.get(i);if(a==b)continue;
            var x=a.input();var y=b.input();
            if(!x.key().equals(y.key()) || !x.revision().equals(y.revision()) || !x.origin().equals(y.origin()) || !x.participation().equals(y.participation())
                || x.motion()!=y.motion() || !a.topologyHash().equals(b.topologyHash()) || !a.positionHash().equals(b.positionHash())
                || !a.appearanceHash().equals(b.appearanceHash()) || !a.shadingHash().equals(b.shadingHash()) || !Arrays.equals(x.current().rows(),y.current().rows()))return false;
        }
        return true;
    }
}
