package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.List;
import dev.rt_render_experiment.contract.FluidInputs;
import dev.rt_render_experiment.contract.SceneInputs;


public final class FluidCompiler {
    private static final FluidInputs.Face[] FACES=FluidInputs.Face.values();
    public record Heights(float northWest,float southWest,float southEast,float northEast) {}
    public Heights heights(FluidInputs.Cell cell) {
        float self=height(cell.neighborhood().get(4));
        if(self>=1)return new Heights(1,1,1,1);
        float north=height(cell.neighborhood().get(1)),south=height(cell.neighborhood().get(7));
        float west=height(cell.neighborhood().get(3)),east=height(cell.neighborhood().get(5));
        return new Heights(average(self,north,west,height(cell.neighborhood().get(0))),
            average(self,south,west,height(cell.neighborhood().get(6))),average(self,south,east,height(cell.neighborhood().get(8))),
            average(self,north,east,height(cell.neighborhood().get(2))));
    }
    private static float height(FluidInputs.Sample sample) { return sample.sameFluid()?sample.sameAbove()?1:sample.ownHeight():sample.solid()?-1:0; }
    private static float average(float self,float a,float b,float corner) {
        if(a>=1 || b>=1 || (a>0 || b>0) && corner>=1)return 1;
        float sum=0,weight=0;

        float[] values=a>0 || b>0?new float[]{corner,self,b,a}:new float[]{self,b,a};
        for(float value:values)if(value>=0) { float w=value>=0.8f?10:1; sum+=value*w; weight+=w; }
        return weight>0?sum/weight:0;
    }
    public List<SceneInputs.Primitive> compile(FluidInputs.Cell cell) {
        Heights h=heights(cell);return faces(cell,h,admittedFaces(cell,h));
    }




    List<SceneInputs.Primitive> interfaces(FluidInputs.Cell cell,int mask) {
        if(cell.volume().shape()!=FluidInputs.VolumeShape.HEIGHT_FIELD_CELL || (mask&~63)!=0)
            throw new IllegalArgumentException("Fluid interface requires declared height-field occupancy");
        return faces(cell,heights(cell),mask);
    }
    private static List<SceneInputs.Primitive> faces(FluidInputs.Cell cell,Heights h,int open) {
        var result=new ArrayList<SceneInputs.Primitive>(6);
        float[][] top={{0,h.northWest,0},{0,h.southWest,1},{1,h.southEast,1},{1,h.northEast,0}};
        boolean flowing=cell.flowX()!=0 || cell.flowZ()!=0;
        float[][] uv={{0,0},{0,1},{1,1},{1,0}};
        if(flowing) {
            float angle=(float)Math.atan2(cell.flowZ(),cell.flowX())-(float)Math.PI/2;
            float sine=(float)Math.sin(angle)*0.25f,cosine=(float)Math.cos(angle)*0.25f;
            uv=new float[][]{{0.5f-cosine-sine,0.5f-cosine+sine},{0.5f-cosine+sine,0.5f+cosine+sine},
                {0.5f+cosine+sine,0.5f+cosine-sine},{0.5f+cosine-sine,0.5f-cosine-sine}};
        }
        if(open(open,FluidInputs.Face.UP))result.add(face(cell,FluidInputs.Face.UP,top,uv,flowing?cell.flowing():cell.still()));
        if(open(open,FluidInputs.Face.DOWN))result.add(face(cell,FluidInputs.Face.DOWN,new float[][]{{0,0,0},{1,0,0},{1,0,1},{0,0,1}},
            new float[][]{{0,0},{1,0},{1,1},{0,1}},cell.still()));
        side(result,cell,open,FluidInputs.Face.NORTH,new float[][]{top[0],top[3],{1,0,0},{0,0,0}});
        side(result,cell,open,FluidInputs.Face.SOUTH,new float[][]{top[2],top[1],{0,0,1},{1,0,1}});
        side(result,cell,open,FluidInputs.Face.WEST,new float[][]{top[1],top[0],{0,0,0},{0,0,1}});
        side(result,cell,open,FluidInputs.Face.EAST,new float[][]{top[3],top[2],{1,0,1},{1,0,0}});
        return List.copyOf(result);
    }
    private static boolean open(int mask,FluidInputs.Face face) { return (mask&(1<<face.ordinal()))!=0; }
    public int admittedFaces(FluidInputs.Cell cell) { return admittedFaces(cell,heights(cell)); }
    private static int admittedFaces(FluidInputs.Cell cell,Heights h) {
        int admitted=cell.candidateFaces();
        for(var face:FACES)if(open(admitted,face)) {
            float height=switch(face) {
                case UP -> Math.min(Math.min(h.northWest,h.southWest),Math.min(h.northEast,h.southEast));
                case NORTH -> Math.max(h.northWest,h.northEast);case SOUTH -> Math.max(h.southWest,h.southEast);
                case WEST -> Math.max(h.northWest,h.southWest);case EAST -> Math.max(h.northEast,h.southEast);case DOWN -> 1;
            };
            if(occluded(cell.neighbors().get(face.ordinal()),face,height))admitted&=~(1<<face.ordinal());
        }
        return admitted;
    }

    public static boolean occluded(FluidInputs.FaceCoverage coverage,FluidInputs.Face face,float height) {
        if(!Float.isFinite(height) || height<0 || height>1)throw new IllegalArgumentException("Invalid fluid height");
        if(coverage.rectangles().isEmpty() || height<=coverage.tolerance())return false;
        if(face==FluidInputs.Face.UP && Math.abs(1.0-height)>coverage.tolerance())return false;
        return covered(coverage,face==FluidInputs.Face.DOWN || face==FluidInputs.Face.UP?1:height);
    }
    private static boolean covered(FluidInputs.FaceCoverage coverage,double height) {
        double tolerance=coverage.tolerance();if(height<=tolerance)return false;
        var rectangles=coverage.rectangles();
        if(rectangles.size()==1) {
            var r=rectangles.getFirst();return r.minU()<=tolerance && r.maxU()>=1-tolerance && r.minV()<=tolerance && r.maxV()>=height-tolerance;
        }
        return FluidFaceCoverage.covered(rectangles,height,tolerance);
    }
    private static void side(List<SceneInputs.Primitive> output,FluidInputs.Cell cell,int open,FluidInputs.Face face,float[][] points) {
        if(!open(open,face))return;
        var surface=(cell.overlayFaces() & (1<<face.ordinal()))!=0?cell.overlay():cell.flowing();
        output.add(face(cell,face,points,new float[][]{{0,(1-points[0][1])*0.5f},{0.5f,(1-points[1][1])*0.5f},{0.5f,0.5f},{0,0.5f}},surface));
    }
    private static SceneInputs.Primitive face(FluidInputs.Cell cell,FluidInputs.Face face,float[][] points,float[][] uv,SceneInputs.Surface source) {
        var corners=new ArrayList<SceneInputs.Corner>(4);
        float[] a=points[0],b=points[1],c=points[2];
        float nx=(b[1]-a[1])*(c[2]-a[2])-(b[2]-a[2])*(c[1]-a[1]);
        float ny=(b[2]-a[2])*(c[0]-a[0])-(b[0]-a[0])*(c[2]-a[2]);
        float nz=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
        float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz); if(!(length>0)) { nx=0;ny=1;nz=0;length=1; }
        var normal=new SceneInputs.Vec3(nx/length,ny/length,nz/length);
        for(int i=0;i<4;i++)corners.add(new SceneInputs.Corner(new SceneInputs.Vec3(cell.position().x()+points[i][0],cell.position().y()+points[i][1],cell.position().z()+points[i][2]),
            uv[i][0],uv[i][1],cell.tint(),normal,new SceneInputs.Vec3(0,0,0)));

        var surface=new SceneInputs.Surface(source.key(),source.material(),source.properties(),source.colorResource(),source.emissionResource(),
            source.coverage(),source.cutoff(),true,source.medium(),source.layer(),source.layerSeparation(),source.layers(),source.hostOcclusion(),source.boundary());
        return new SceneInputs.Primitive(cell.part(),Math.addExact(Math.multiplyExact(cell.ordinal(),6),face.ordinal()),surface,corners);
    }
}
