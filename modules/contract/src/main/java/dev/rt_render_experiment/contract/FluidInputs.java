package dev.rt_render_experiment.contract;

import java.util.List;
import java.util.Objects;


public final class FluidInputs {
    public static final int API_VERSION=3;
    private FluidInputs() {}
    public enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    public enum VolumeShape { UNQUALIFIED, HEIGHT_FIELD_CELL }







    public record VolumeFacts(VolumeShape shape,int sameFluidFaces) {
        public static final VolumeFacts UNKNOWN=new VolumeFacts(VolumeShape.UNQUALIFIED,0);
        public VolumeFacts { Objects.requireNonNull(shape);if((sameFluidFaces&~63)!=0)throw new IllegalArgumentException("Invalid fluid connections"); }
        public boolean connected(Face face) { return (sameFluidFaces&(1<<face.ordinal()))!=0; }
    }
    public record Sample(float ownHeight,boolean sameFluid,boolean sameAbove,boolean solid) {
        public Sample { SceneInputs.unit(ownHeight); }
    }

    public record Rectangle(double minU,double minV,double maxU,double maxV) {
        public Rectangle {
            if(!Double.isFinite(minU) || !Double.isFinite(minV) || !Double.isFinite(maxU) || !Double.isFinite(maxV)
                || minU<0 || minV<0 || maxU>1 || maxV>1 || minU>=maxU || minV>=maxV)
                throw new IllegalArgumentException("Invalid prepared face rectangle");
            if(minU==0)minU=0;if(minV==0)minV=0;
        }
    }





    public record FaceCoverage(List<Rectangle> rectangles,double tolerance) {
        public static final FaceCoverage CLEAR=new FaceCoverage(List.of(),0);
        public FaceCoverage {
            rectangles=List.copyOf(rectangles);
            if(!Double.isFinite(tolerance) || tolerance<0 || tolerance>=.5)throw new IllegalArgumentException("Invalid face coordinate tolerance");
        }

        public long bytes() { return Math.addExact(Double.BYTES,Math.multiplyExact((long)rectangles.size(),4*Double.BYTES)); }
    }

    public record Cell(SceneInputs.Key part,long ordinal,SceneInputs.Vec3 position,List<Sample> neighborhood,
                       int candidateFaces,int overlayFaces,float flowX,float flowZ,SceneInputs.Color tint,
                       SceneInputs.Surface still,SceneInputs.Surface flowing,SceneInputs.Surface overlay,List<FaceCoverage> neighbors,VolumeFacts volume) {
        public Cell(SceneInputs.Key part,long ordinal,SceneInputs.Vec3 position,List<Sample> neighborhood,
                    int candidateFaces,int overlayFaces,float flowX,float flowZ,SceneInputs.Color tint,
                    SceneInputs.Surface still,SceneInputs.Surface flowing,SceneInputs.Surface overlay,List<FaceCoverage> neighbors) {
            this(part,ordinal,position,neighborhood,candidateFaces,overlayFaces,flowX,flowZ,tint,still,flowing,overlay,neighbors,VolumeFacts.UNKNOWN);
        }

        public Cell(SceneInputs.Key part,long ordinal,SceneInputs.Vec3 position,List<Sample> neighborhood,
                    int candidateFaces,int overlayFaces,float flowX,float flowZ,SceneInputs.Color tint,
                    SceneInputs.Surface still,SceneInputs.Surface flowing,SceneInputs.Surface overlay) {
            this(part,ordinal,position,neighborhood,candidateFaces,overlayFaces,flowX,flowZ,tint,still,flowing,overlay,
                java.util.Collections.nCopies(6,FaceCoverage.CLEAR));
        }
        public Cell {
            Objects.requireNonNull(part); Objects.requireNonNull(position); neighborhood=List.copyOf(neighborhood);
            Objects.requireNonNull(tint); Objects.requireNonNull(still); Objects.requireNonNull(flowing); Objects.requireNonNull(overlay);
            SceneInputs.finite(flowX); SceneInputs.finite(flowZ);
            neighbors=List.copyOf(neighbors);Objects.requireNonNull(volume);
            if(ordinal<0 || neighborhood.size()!=9 || neighbors.size()!=6 || (candidateFaces & ~63)!=0 || (overlayFaces & ~63)!=0 || !neighborhood.get(4).sameFluid())
                throw new IllegalArgumentException("Incomplete fluid preparation");
        }
        public boolean candidate(Face face) { return (candidateFaces & (1<<face.ordinal()))!=0; }

        public long occlusionBytes() { return 8+neighbors.stream().mapToLong(FaceCoverage::bytes).sum(); }
    }
}
