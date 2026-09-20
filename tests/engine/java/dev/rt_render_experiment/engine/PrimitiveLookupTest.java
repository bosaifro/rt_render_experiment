package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.engine.abi.R2Abi;
import static dev.rt_render_experiment.engine.SceneContractsTest.expect;
import static dev.rt_render_experiment.engine.SceneContractsTest.require;


public final class PrimitiveLookupTest {
    public PrimitiveLookupTest() {}
    @org.junit.jupiter.api.Test public void sourceCorrespondence() throws Exception {
        var source=SceneContractsTest.fixture(1);var base=source.primitives().getFirst();
        var keys=List.of(new SceneInputs.Key(-1,0),new SceneInputs.Key(0,3),new SceneInputs.Key(0,-1),
            new SceneInputs.Key(Long.MIN_VALUE,1),new SceneInputs.Key(Long.MAX_VALUE,1));
        var primitives=new ArrayList<SceneInputs.Primitive>();
        for(int index=0;index<keys.size();index++)for(long ordinal:new long[]{Long.MAX_VALUE,0,7})
            primitives.add(new SceneInputs.Primitive(keys.get(index),ordinal,base.surface(),base.corners()));
        var compiler=new SceneCompiler();var input=geometry(source,primitives,1);var compiled=compiler.compile(input);
        var positions=compiled.positions();var indices=compiled.indices();var corners=compiled.corners();var hash=compiled.topologyHash();
        require(compiled.primitiveIndexBytes()==0,"Unqueried content allocated an identity index");

        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->verify(compiled,primitives));var b=workers.submit(()->verify(compiled,primitives));
            a.get();b.get();
        }
        require(compiled.primitiveIndexBytes()==4L*primitives.size(),"Index payload grew beyond one integer per primitive");
        require(compiled.positions().mismatch(positions)==-1 && compiled.indices().mismatch(indices)==-1
            && compiled.corners().mismatch(corners)==-1 && compiled.topologyHash().equals(hash),"Lookup changed compiled geometry or correspondence");
        require(compiled.findPart(new SceneInputs.Key(99,3),0).isEmpty() && compiled.findPart(keys.getFirst(),8).isEmpty(),"Missing source identity aliased another part");
        var revised=compiler.withInputRevision(compiled,geometry(source,primitives,2));
        require(revised.primitiveIndexBytes()==compiled.primitiveIndexBytes(),"Revision-only publication rebuilt its derived index");
        verify(revised,primitives);
        Collections.reverse(primitives);var rt_render_experimentdered=compiler.compile(geometry(source,primitives,3));
        require(rt_render_experimentdered.primitiveIndexBytes()==0 && !rt_render_experimentdered.topologyHash().equals(hash),"Same-count topology reused a stale index");
        verify(rt_render_experimentdered,primitives);
        var lights=new ArrayList<LightInputs.Source>();
        for(int i=0;i<primitives.size();i++) {
            var primitive=primitives.get(i);
            lights.add(new LightInputs.Area(new SceneInputs.Key(7,i),1,LightInputs.Emission.areaImportance(new SceneInputs.Vec3(1,1,1)),
                source.origin(),0,LightInputs.Stratum.WORLD,rt_render_experimentdered.input().key(),primitive.part(),primitive.ordinal()));
        }
        var revision=new SceneStore.Revision(1,1,List.of(rt_render_experimentdered),rt_render_experimentdered.bytes());
        var joined=new FrameScene(2,revision,null,List.of(),new LightInputs.Publication(1,lights));
        var emission=new LightCompiler().compile(joined.revision().geometry(),joined.revision().lights(),source.origin());
        for(var primitive:primitives) {
            var ref=emission.associations().get(new LightCompiler.Association(source.key(),primitive.part(),primitive.ordinal()));
            var part=rt_render_experimentdered.findPart(primitive.part(),primitive.ordinal()).orElseThrow();
            int first=emission.sources().order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(ref.slot()*R2Abi.SourceRecord.SIZE+R2Abi.SourceRecord.ASSOCIATION+4);
            require(first==part.firstIndex()/3,"Live source compiler associated emission with the pre-rt_render_experimentder primitive");
        }
        var duplicate=new ArrayList<>(primitives);duplicate.add(primitives.getFirst());
        expect(()->compiler.compile(geometry(source,duplicate,4)),"Compiler uniqueness cannot be removed with frame duplicate scans");
    }
    private static void verify(SceneCompiler.Compiled compiled,List<SceneInputs.Primitive> primitives) {
        for(int i=0;i<primitives.size();i++) {
            var primitive=primitives.get(i);var part=compiled.findPart(primitive.part(),primitive.ordinal()).orElseThrow();
            require(part==compiled.parts().get(i) && part.firstVertex()==i*4 && part.firstIndex()==i*6,"Source lookup lost exact corner/index correspondence");
        }
    }
    private static SceneInputs.Geometry geometry(SceneInputs.Geometry source,List<SceneInputs.Primitive> primitives,long topology) {
        var revision=new SceneInputs.Revision(1,topology,0,1,1,1,1);
        return new SceneInputs.Geometry(source.key(),revision,source.origin(),source.current(),source.previous(),false,
            source.motion(),source.participation(),primitives);
    }
}
