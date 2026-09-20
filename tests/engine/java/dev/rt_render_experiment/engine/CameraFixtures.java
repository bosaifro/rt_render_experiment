package dev.rt_render_experiment.engine;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import dev.rt_render_experiment.contract.HostExecution;
import dev.rt_render_experiment.contract.SceneInputs;
import org.joml.Matrix4f;
import org.joml.Vector3f;


final class CameraFixtures {
    private CameraFixtures() {}
    private static void write(java.nio.file.Path path,ByteBuffer bytes) throws java.io.IOException {
        byte[] data=new byte[bytes.remaining()];bytes.duplicate().get(data);java.nio.file.Files.write(path,data,java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE);
    }
    private static HostExecution.Window window(long serial,HostExecution.Stage stage,HostExecution.ImageGrant... images) { return new HostExecution.Window(1,serial,serial,stage,List.of(),List.of(images)); }
    static SceneCompiler.Compiled mesh(SceneInputs.Origin origin,boolean pass) {
        var base=SceneFixtures.prepare(false).geometry().getFirst().input();var p=base.primitives().getFirst();var s=p.surface();
        var front=new SceneInputs.Surface(s.key(),s.material(),s.properties(),s.colorResource(),s.emissionResource(),s.coverage(),s.cutoff(),s.doubleSided(),s.medium(),s.layer(),s.layerSeparation(),s.layers(),pass?SceneInputs.HostOcclusion.PASS:SceneInputs.HostOcclusion.BLOCK);
        var primitives=new java.util.ArrayList<SceneInputs.Primitive>();
        for(int layer=0;layer<2;layer++) {
            float z=layer==0?-2:-3;
            var corners=p.corners().stream().map(c->new SceneInputs.Corner(new SceneInputs.Vec3(c.position().x()*8,c.position().y()*8,z),c.u(),c.v(),c.tint(),c.normal(),c.tangent())).toList();
            primitives.add(new SceneInputs.Primitive(p.part(),layer,layer==0?front:s,corners));
        }
        return new SceneCompiler().compile(new SceneInputs.Geometry(base.key(),base.revision(),origin,base.current(),base.previous(),false,base.motion(),base.participation(),primitives));
    }
    static float[] rows(Matrix4f matrix) { var result=new float[16];for(int row=0;row<4;row++)for(int column=0;column<4;column++)result[row*4+column]=matrix.get(column,row);return result; }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }}
