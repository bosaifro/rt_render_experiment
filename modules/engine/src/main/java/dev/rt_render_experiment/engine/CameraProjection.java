package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.contract.SceneInputs;


public final class CameraProjection {
    public static final float MAXIMUM_RAY_DISTANCE=10_000;
    public record Ray(SceneInputs.Vec3 origin,SceneInputs.Vec3 direction,float minimum,float maximum) {}

    public record Clip(double x,double y,double z,double w) {}
    private final float[] inverse;
    private final float[] current;
    private final RenderFrame.DepthConvention depth;
    private final SceneInputs.Vec3 eye;

    CameraProjection(float[] inverse,float[] current,RenderFrame.DepthConvention depth) {
        this.inverse=inverse;this.current=current;this.depth=depth;


        double w=inverse[14];
        if(w==0)throw new IllegalArgumentException("The current camera contract requires a finite pinhole eye; parallel projections need a declared ray-origin field");
        eye=new SceneInputs.Vec3((float)(inverse[2]/w),(float)(inverse[6]/w),(float)(inverse[10]/w));
        for(float x:new float[]{-1,0,1})for(float y:new float[]{-1,0,1}) {
            ray(x,y);
            double[] point=unproject(x,y,.5f);
            double[] clip=multiply(current,point[0]/point[3],point[1]/point[3],point[2]/point[3],1);
            if(!Double.isFinite(clip[3]) || clip[3]==0 || Math.abs(clip[0]/clip[3]-x)>1e-3 || Math.abs(clip[1]/clip[3]-y)>1e-3
                || Math.abs(clip[2]/clip[3]-.5)>1e-3)throw new IllegalArgumentException("Inconsistent camera forward/inverse matrices");
        }
    }
    public SceneInputs.Vec3 eyeOffset() { return eye; }
    public Clip project(SceneInputs.Origin point,SceneInputs.Origin reference) {
        var clip=multiply(current,point.x()-reference.x(),point.y()-reference.y(),point.z()-reference.z(),1);
        return new Clip(clip[0],clip[1],clip[2],clip[3]);
    }

    public Ray ray(float x,float y) {
        if(!Float.isFinite(x) || !Float.isFinite(y))throw new IllegalArgumentException("Non-finite camera sample");
        double[] near=unproject(x,y,depth.near),middle=unproject(x,y,.5f),far=unproject(x,y,depth.far);
        if(near[3]==0 || middle[3]==0)throw new IllegalArgumentException("Camera clipping plane crosses infinity");
        double dx=middle[0]/middle[3]-eye.x(),dy=middle[1]/middle[3]-eye.y(),dz=middle[2]/middle[3]-eye.z();
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);dx/=length;dy/=length;dz/=length;
        double minimum=(near[0]/near[3]-eye.x())*dx+(near[1]/near[3]-eye.y())*dy+(near[2]/near[3]-eye.z())*dz;
        double maximum=far[3]==0?Double.POSITIVE_INFINITY:(far[0]/far[3]-eye.x())*dx+(far[1]/far[3]-eye.y())*dy+(far[2]/far[3]-eye.z())*dz;
        if(!Double.isFinite(minimum) || minimum<0 || !(maximum>minimum) || minimum>=MAXIMUM_RAY_DISTANCE)
            throw new IllegalArgumentException("Invalid or reversed camera clipping interval");
        return new Ray(eye,new SceneInputs.Vec3((float)dx,(float)dy,(float)dz),(float)minimum,(float)Math.min(maximum,MAXIMUM_RAY_DISTANCE));
    }
    private double[] unproject(float x,float y,float z) { return multiply(inverse,x,y,z,1); }
    private static double[] multiply(float[] matrix,double x,double y,double z,double w) {
        var result=new double[4];for(int row=0;row<4;row++)result[row]=matrix[row*4]*x+matrix[row*4+1]*y+matrix[row*4+2]*z+matrix[row*4+3]*w;return result;
    }
}
