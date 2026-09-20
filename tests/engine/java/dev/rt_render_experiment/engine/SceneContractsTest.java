package dev.rt_render_experiment.engine;

import java.util.List;
import dev.rt_render_experiment.contract.MaterialInputs;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;
import static dev.rt_render_experiment.contract.SceneInputs.*;


public final class SceneContractsTest {
    public SceneContractsTest() {}
    @org.junit.jupiter.api.Test public void productionContracts() { main(new String[0]); }
    public static void main(String[] args) {
        SceneCompiler compiler = new SceneCompiler();
        Geometry source = fixture(1);
        var compiled = compiler.compile(source);
        require(compiled.vertexCount() == 4 && compiled.triangleCount() == 2, "quad topology");
        require(compiled.indices().getInt(12) == 2 && compiled.indices().getInt(20) == 0, "split/winding");
        require(compiled.corners().getFloat(16) == 1 && compiled.corners().getFloat(SceneCompiler.CORNER_STRIDE+20) == 1, "per-corner tint");
        require(compiled.corners().getFloat(48) == 1, "authored tangent retained");
        require(compiled.input().origin().x() == -30_000_000, "large origin precision");
        require(compiled.input().current().determinant() < 0, "negative placement preserved");
        var originalPrimitive=source.primitives().getFirst();
        var folded=new java.util.ArrayList<>(originalPrimitive.corners()); folded.set(1,folded.getFirst());
        var partiallyDegenerate=new Geometry(source.key(),source.revision(),source.origin(),source.current(),source.previous(),false,
            source.motion(),source.participation(),List.of(new Primitive(originalPrimitive.part(),originalPrimitive.ordinal(),originalPrimitive.surface(),folded)));
        var retained=compiler.compile(partiallyDegenerate);
        require(retained.triangleCount()==2 && retained.indices().getInt(12)==2,"valid second triangle and source ordinal survive degenerate first triangle");
        expect(() -> compiled.positions().putFloat(0, 3), "immutable compiled publication");
        float[] matrix = Transform.identity().rows(); var transform = new Transform(matrix); matrix[0] = 4;
        require(transform.get(0,0) == 1, "input matrix copied");
        SceneStore store = new SceneStore(compiler, new SceneStore.Budget(2, 8192)); store.world(1);
        var old = store.request(source.key(), source.revision(), 4096);
        var replacement = fixture(2); var current = store.request(source.key(), replacement.revision(), 4096);
        require(!store.publish(result(old,source)), "out-of-order result rejected");
        require(store.pendingJobs() == 1 && store.pendingBytes() == 4096, "stale result cannot release current budget");
        require(store.publish(result(current,replacement)), "latest result published");
        var previous = store.snapshot();
        var failed = store.request(source.key(), fixture(3).revision(), 4096);
        require(!store.publish(new PreparationResult(failed, PreparationStatus.FAILED, List.of(), 0, "producer failed")), "failure rejected");
        require(store.snapshot().serial() == previous.serial(), "predecessor retained");
        var unloaded = store.request(source.key(), fixture(3).revision(),4096); store.world(2);
        require(!store.publish(result(unloaded,fixture(3))) && store.snapshot().geometry().isEmpty(), "old-world callback rejected");
        HostExecution.Transaction tx = new HostExecution.Transaction(1);
        expect(tx::accepted, "capture is not recording"); tx.recorded(); tx.accepted(); tx.submitted(7);
        expect(() -> tx.completed(6), "reservation is not completion"); expect(tx::cancel, "submitted work cannot be cancelled");
        tx.completed(7); tx.retire(); require(tx.state() == HostExecution.State.RETIRED,"retirement");
        System.out.println("Scene contracts PASS: real compiler, immutable corners, negative placement, stale/rt_render_experimentdered/unloaded results, predecessor retention, byte budgets and submission state");
    }
    static Geometry fixture(long topology) {
        Key key = new Key(77,13), texture = new Key(0,0);
        Surface surface = new Surface(new Key(9,8),2,0,texture,new Key(0,0),Coverage.OPAQUE,0.5f,true,0,0,0,MaterialInputs.Layers.plain(texture),HostOcclusion.BLOCK);
        Vec3 normal = new Vec3(0,0,1), tangent = new Vec3(1,0,0);
        List<Corner> corners = List.of(
            new Corner(new Vec3(-1,-1,0),0,0,new Color(1,0,0,1),normal,tangent),
            new Corner(new Vec3(1,-1,0),1,0,new Color(0,1,0,1),normal,tangent),
            new Corner(new Vec3(1,1,0),1,1,new Color(0,0,1,1),normal,tangent),
            new Corner(new Vec3(-1,1,0),0,1,new Color(1,1,1,1),normal,tangent));
        return new Geometry(key,new Revision(1,topology,0,1,1,1,1),new Origin(-30_000_000,0,0),
            new Transform(new float[]{-1,0,0,0,0,1,0,0,0,0,1,0}),Transform.identity(),false,Motion.STATIC,
            new Participation(Participation.WORLD,true),List.of(new Primitive(new Key(0,3),0,surface,corners)));
    }
    private static PreparationResult result(PreparationRequest request,Geometry input) {
        return new PreparationResult(request,PreparationStatus.READY,List.of(input),4096,"ready");
    }
    static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
    static void expect(Runnable action,String message) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.nio.ReadOnlyBufferException expected) { return; }
        throw new AssertionError("Expected rejection: " + message);
    }
}
