package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.contract.SceneInputs;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CameraProjectionTest {
    @Test void sourceMatrixPlacementDoesNotChangeTheCamera() {
        for(var depth:RenderFrame.DepthConvention.values()) {
            var lens=lens(depth);var pose=new Matrix4f().translate(.12f,-.03f,.01f).rotateY(.3f).rotateZ(.02f).scale(1.03f,.98f,1);
            var a=frame(new Matrix4f(),new Matrix4f(lens).mul(pose),depth);
            var b=frame(pose,lens,depth);
            var expectedEye=new Matrix4f(pose).invert().transformPosition(new Vector3f());
            assertEquals(expectedEye.x,a.camera().eyeOffset().x(),2e-6);
            assertEquals(expectedEye.y,a.camera().eyeOffset().y(),2e-6);
            assertEquals(expectedEye.z,a.camera().eyeOffset().z(),2e-6);
            for(float x:new float[]{-.9f,0,.9f})for(float y:new float[]{-.9f,0,.9f}) {
                var ray=a.camera().ray(x,y);assertEquals(ray,b.camera().ray(x,y));
                var point=new Vector3f(ray.direction().x(),ray.direction().y(),ray.direction().z()).mul(3).add(expectedEye);
                var clip=a.camera().project(new SceneInputs.Origin(a.origin().x()+point.x,a.origin().y()+point.y,a.origin().z()+point.z),a.origin());
                assertEquals(x,clip.x()/clip.w(),1e-5);assertEquals(y,clip.y()/clip.w(),1e-5);
                var projected=new Matrix4f(lens).mul(pose).transformProject(point);
                assertEquals(x,projected.x,1e-5);assertEquals(y,projected.y,1e-5);
                var near=new Vector3f(ray.direction().x(),ray.direction().y(),ray.direction().z()).mul(ray.minimum()).add(expectedEye);
                assertEquals(depth.near,new Matrix4f(lens).mul(pose).transformProject(near).z,2e-5);
                assertTrue(ray.maximum()>ray.minimum());
            }
        }
    }
    @Test void invalidCameraFactsRejectBeforeGpuWork() {
        var lens=lens(RenderFrame.DepthConvention.REVERSED);
        assertThrows(IllegalArgumentException.class,()->frame(new Matrix4f(),lens,RenderFrame.DepthConvention.FORWARD));
        assertThrows(IllegalArgumentException.class,()->frame(new Matrix4f(),new Matrix4f().ortho(-1,1,-1,1,.05f,100,true),RenderFrame.DepthConvention.FORWARD));
        assertThrows(IllegalArgumentException.class,()->new RenderFrame(16,16,0,new SceneInputs.Origin(0,64,0),CameraFixtures.rows(new Matrix4f(lens).invert()),
            CameraFixtures.rows(new Matrix4f(lens).scale(2,1,1)),CameraFixtures.rows(lens),SceneFixtures.frame().environment(),RenderFrame.DepthConvention.REVERSED));
        assertThrows(IllegalArgumentException.class,()->frame(new Matrix4f(),lens,RenderFrame.DepthConvention.REVERSED).camera().ray(Float.NaN,0));
    }
    @Test void cameraDiscontinuityUsesTheEyeAndNotOnlyTheSceneAnchor() {
        var history=new FrameCameraHistory();var first=frame(new Matrix4f(),lens(RenderFrame.DepthConvention.FORWARD),RenderFrame.DepthConvention.FORWARD);
        history.submitted(1,0,history.resolve(1,0,first));
        var moved=frame(new Matrix4f().translate(-33,0,0),lens(RenderFrame.DepthConvention.FORWARD),RenderFrame.DepthConvention.FORWARD);
        assertEquals(first.origin(),moved.origin());assertFalse(history.resolve(1,0,moved).previousValid());
        assertEquals(30_000_033,moved.eye().x(),1e-5);
    }
    private static Matrix4f lens(RenderFrame.DepthConvention depth) { return new Matrix4f().perspective(1.1f,1,depth==RenderFrame.DepthConvention.REVERSED?100:.05f,depth==RenderFrame.DepthConvention.REVERSED?.05f:100,true); }
    private static RenderFrame frame(Matrix4f view,Matrix4f projection,RenderFrame.DepthConvention depth) {
        return RenderFrame.fromCamera(16,16,0,new SceneInputs.Origin(30_000_000,64,0),CameraFixtures.rows(view),CameraFixtures.rows(projection),SceneFixtures.frame().environment(),depth);
    }
}
