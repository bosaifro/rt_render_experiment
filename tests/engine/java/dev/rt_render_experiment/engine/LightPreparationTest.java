package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import dev.rt_render_experiment.contract.LightInputs;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import dev.rt_render_experiment.engine.abi.R2Abi;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LightPreparationTest {
    @Test void exactDerivativeDependenciesAndOrdering() {
        var compiler=new LightCompiler();var base=LightPreparationFixtures.at(0);
        var first=compiler.prepare(1,base.geometry(),base.lights(),base.origin(),null);
        assertEquals(new LightCompiler.Work(4,0,1,0,true),first.work());
        var alternate=new ArrayList<>(base.lights());Collections.reverse(alternate);
        var same=compiler.prepare(1,LightPreparationFixtures.at(1).geometry(),alternate,base.origin(),first.compiled());
        assertSame(first.compiled(),same.compiled());assertEquals(new LightCompiler.Work(0,4,0,1,false),same.work());

        same.compiled().sources().position(16);same.compiled().cells().limit(0);
        assertEquals(4*R2Abi.SourceRecord.SIZE,same.compiled().sources().remaining());
        assertEquals(R2Abi.LightCellRecord.SIZE,same.compiled().cells().remaining());
        for(int step:new int[]{2,3,4,5,6,7,8,9,10,11}) {
            var input=LightPreparationFixtures.at(step);var result=compiler.prepare(1,input.geometry(),input.lights(),input.origin(),first.compiled());
            if(step==2 || step==3) {
                assertEquals(new LightCompiler.Work(1,3,0,1,false),result.work(),"Only the changed hand/emission record should be encoded");
                assertTrue(result.compiled().sharesCells(first.compiled()));
            }
            if(step==5) {
                assertEquals(new LightCompiler.Work(1,3,0,1,false),result.work(),"Slot relocation must relink without recomputing the area");
                var association=result.compiled().associations().values().iterator().next();
                assertEquals(2,result.compiled().sources().getInt(association.slot()*R2Abi.SourceRecord.SIZE+R2Abi.SourceRecord.ASSOCIATION));
            }
            if(step==9)assertSame(first.compiled(),result.compiled(),"Texture evaluation belongs to hit material resolution, not invariant source geometry");
            if(step==4 || step==6 || step==7 || step==8 || step==11)assertEquals(1,result.work().preparedAreas());
            if(step==10)assertEquals(new LightCompiler.Work(4,0,1,0,false),result.work(),"Origin rebasing must preserve the prior float arithmetic");
        }
    }
    @Test void failedCandidatesAndAbsentIdentitiesStayBounded() {
        var compiler=new LightCompiler();var input=LightPreparationFixtures.at(0);
        var original=compiler.prepare(1,input.geometry(),input.lights(),input.origin(),null).compiled();
        var source=input.lights().getLast();var area=(LightInputs.Area)source;
        var invalid=new LightInputs.Area(area.key(),area.revision(),area.emission(),area.samplingOrigin(),area.medium(),area.stratum(),area.geometry(),area.part(),99);
        var changed=new ArrayList<>(input.lights());changed.set(changed.size()-1,invalid);
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(1,input.geometry(),changed,input.origin(),original));
        var duplicate=new ArrayList<>(input.lights());duplicate.add(source);
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(1,input.geometry(),duplicate,input.origin(),original));
        duplicate.set(duplicate.size()-1,new LightInputs.Area(new SceneInputs.Key(903,1),1,area.emission(),area.samplingOrigin(),0,area.stratum(),area.geometry(),area.part(),area.ordinal()));
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(1,input.geometry(),duplicate,input.origin(),original));
        assertThrows(IllegalArgumentException.class,()->new LightCompiler().prepare(1,input.geometry(),input.lights(),input.origin(),original));
        var view=new ArrayList<>(input.geometry());view.set(1,LightPreparationFixtures.participation(view.get(1),new Participation(Participation.VIEWMODEL,true)));
        assertThrows(IllegalArgumentException.class,()->compiler.prepare(1,view,input.lights(),input.origin(),original));
        assertSame(original,compiler.prepare(1,input.geometry(),input.lights(),input.origin(),original).compiled());
        var current=original;
        for(int i=0;i<2048;i++) {
            var point=new LightInputs.Point(new SceneInputs.Key(904,i),1,input.lights().getFirst().emission(),input.origin(),0,LightInputs.Stratum.WORLD,input.origin(),.12f);
            current=compiler.prepare(1,List.of(),List.of(point),input.origin(),current).compiled();
            assertEquals(1,current.retainedSources());
        }
        var empty=compiler.prepare(1,List.of(),List.of(),input.origin(),current).compiled();assertEquals(0,empty.retainedSources());
        var restored=compiler.prepare(1,input.geometry(),input.lights(),input.origin(),empty).compiled();
        assertEquals(original.sources().getLong(R2Abi.SourceRecord.IDENTITY),restored.sources().getLong(R2Abi.SourceRecord.IDENTITY),"Logical source key changed");
        assertNotEquals(original.sources().getInt(R2Abi.SourceRecord.ASSOCIATION+12),restored.sources().getInt(R2Abi.SourceRecord.ASSOCIATION+12),"Retired association token was reused");
    }
    @Test void uncachedReferenceAndReusedPreprocessingAgreeExactly() {
        var cached=new LightCompiler();var reference=new LightCompiler(LightCompiler.Reuse.REBUILD);
        LightCompiler.Compiled a=null,b=null;
        for(int step=0;step<12;step++) {
            var input=LightPreparationFixtures.at(step);
            var prepared=cached.prepare(1,input.geometry(),input.lights(),input.origin(),a);
            var rebuilt=reference.prepare(1,input.geometry(),input.lights(),input.origin(),b);
            a=prepared.compiled();b=rebuilt.compiled();
            assertEquals(-1,a.sources().mismatch(b.sources()),"Source records differ at step "+step);
            assertEquals(-1,a.cells().mismatch(b.cells()),"Cell records differ at step "+step);assertEquals(a.associations(),b.associations());
        }
    }
    @Test void worldCellAndSourceMeaningInvalidateOnlyTheirDerivatives() {
        var compiler=new LightCompiler();var input=LightPreparationFixtures.at(0);
        var initial=compiler.prepare(1,input.geometry(),input.lights(),input.origin(),null).compiled();
        var original=(LightInputs.Point)input.lights().getFirst();
        var movedOrigin=new SceneInputs.Origin(-32,original.position().y(),original.position().z());
        var moved=new LightInputs.Point(original.key(),2,original.emission(),movedOrigin,0,original.stratum(),movedOrigin,original.radius());
        var sources=new ArrayList<>(input.lights());sources.set(0,moved);
        var changed=compiler.prepare(1,input.geometry(),sources,input.origin(),initial);
        assertTrue(changed.work().rebuiltLayout());assertEquals(0,changed.work().preparedAreas());assertEquals(1,changed.work().reusedAreas());
        assertEquals(-2,changed.compiled().cells().getInt(R2Abi.LightCellRecord.COORDINATECOUNT));
        var altered=new LightInputs.Point(original.key(),original.revision(),new LightInputs.Emission(10,2,new SceneInputs.Vec3(.1f,.2f,.3f)),
            original.samplingOrigin(),1,original.stratum(),original.position(),.24f);
        sources.set(0,altered);var changedMeaning=compiler.prepare(1,input.geometry(),sources,input.origin(),initial);
        assertEquals(new LightCompiler.Work(1,3,0,1,false),changedMeaning.work());
        assertEquals(10,changedMeaning.compiled().sources().getInt(R2Abi.SourceRecord.DEFINITION+4));
        assertEquals(1,changedMeaning.compiled().sources().getInt(R2Abi.SourceRecord.DEFINITION+8));
        var priorWorld=compiler.prepare(1,List.of(),List.of(original),input.origin(),null).compiled();
        var nextWorld=compiler.prepare(2,List.of(),List.of(original),input.origin(),priorWorld);
        assertEquals(new LightCompiler.Work(1,0,0,0,true),nextWorld.work());
        assertNotEquals(priorWorld.sources().getInt(R2Abi.SourceRecord.ASSOCIATION+12),nextWorld.compiled().sources().getInt(R2Abi.SourceRecord.ASSOCIATION+12));
    }
}
