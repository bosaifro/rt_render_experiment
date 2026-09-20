package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.engine.abi.R2Abi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.MediumInputs;
import dev.rt_render_experiment.contract.SceneInputs;


final class MediumDefinitions {
    final MediumInputs.Domain domain;
    private final Map<Long,Integer> offsets;
    private final Map<Long,MediumInputs.Definition> definitions;
    private final ByteBuffer bytes;
    private MediumDefinitions(MediumInputs.Domain domain) {
        this.domain=domain;var locations=new HashMap<Long,Integer>();var values=new HashMap<Long,MediumInputs.Definition>();
        var data=ByteBuffer.allocate(Math.multiplyExact(domain.volumes().size(),R2Abi.MediumRecord.SIZE)).order(ByteOrder.LITTLE_ENDIAN);
        int offset=0;
        for(var volume:domain.volumes()) {
            var medium=volume.medium();var a=medium.absorption();var s=medium.scattering();
            locations.put(volume.identity(),offset);values.put(volume.identity(),medium);
            R2Abi.MediumRecord.identity(data,offset,volume.identity());
            R2Abi.MediumRecord.kind(data,offset,medium.kind().encoding());
            R2Abi.MediumRecord.optical(data,offset,medium.ior(),a.x(),a.y(),a.z());
            R2Abi.MediumRecord.scattering(data,offset,s.x(),s.y(),s.z(),0);
            offset+=R2Abi.MediumRecord.SIZE;
        }
        offsets=Map.copyOf(locations);definitions=Map.copyOf(values);bytes=data.asReadOnlyBuffer();
    }
    static MediumDefinitions prepare(MediumInputs.Domain domain,List<SceneCompiler.Compiled> geometry,MediumDefinitions before) {
        if(domain==null)return null;
        var found=new HashSet<Long>();
        for(var mesh:geometry)for(var part:mesh.parts())if(part.surface().boundary()!=SceneInputs.Boundary.UNQUALIFIED)found.add(part.surface().medium());
        for(var mesh:geometry)for(var contact:mesh.clipContacts())found.add(contact.medium());
        return prepare(domain,found,before);
    }
    static MediumDefinitions prepare(MediumInputs.Domain domain,java.util.Set<Long> found,MediumDefinitions before) {
        if(domain==null) {
            if(!found.isEmpty())throw new IllegalArgumentException("Identified producer boundaries lack medium definitions");
            return null;
        }
        var declared=new HashSet<Long>();for(var volume:domain.volumes())declared.add(volume.identity());
        if(!found.equals(declared))throw new IllegalArgumentException("Published medium definitions and identified scene boundaries disagree");
        return before!=null && before.domain.equals(domain)?before:new MediumDefinitions(domain);
    }
    ByteBuffer bytes() { return bytes.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN); }
    int offset(long identity) {
        var offset=offsets.get(identity);if(offset==null)throw new IllegalArgumentException("Missing published medium identity");return offset;
    }
    MediumInputs.Definition definition(long identity) { return definitions.get(identity); }
    void requireFrameOrigin(RenderFrame frame) {
        var origin=frame.initialMedia().orElseThrow(()->new IllegalArgumentException("Published medium domain requires a classified origin"));
        for(var enclosure:origin.enclosures())if(!enclosure.medium().equals(definitions.get(enclosure.volume())))
            throw new IllegalArgumentException("Origin and GPU scene use different medium definitions");
    }
}
