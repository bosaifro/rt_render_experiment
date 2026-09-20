package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import dev.rt_render_experiment.contract.FluidInputs;


final class FluidFaceCoverage {
    private FluidFaceCoverage() {}
    private record Edge(double u,int low,int high,int delta) {}
    static boolean covered(List<FluidInputs.Rectangle> rectangles,double height,double tolerance) {
        double[] coordinates=new double[Math.addExact(2,Math.multiplyExact(rectangles.size(),2))];coordinates[1]=height;
        for(int i=0;i<rectangles.size();i++) {
            coordinates[2+i*2]=Math.min(rectangles.get(i).minV(),height);
            coordinates[3+i*2]=Math.min(rectangles.get(i).maxV(),height);
        }
        Arrays.sort(coordinates);int unique=1;
        for(int i=1;i<coordinates.length;i++)if(coordinates[i]!=coordinates[unique-1])coordinates[unique++]=coordinates[i];
        coordinates=Arrays.copyOf(coordinates,unique);
        var edges=new ArrayList<Edge>(Math.multiplyExact(rectangles.size(),2));
        for(var rectangle:rectangles) {
            int low=Arrays.binarySearch(coordinates,Math.min(rectangle.minV(),height));
            int high=Arrays.binarySearch(coordinates,Math.min(rectangle.maxV(),height));
            if(low<high) {
                edges.add(new Edge(rectangle.minU(),low,high,1));edges.add(new Edge(rectangle.maxU(),low,high,-1));
            }
        }
        if(edges.isEmpty())return false;
        edges.sort(Comparator.comparingDouble(Edge::u));var gaps=new Gaps(coordinates);double position=0;
        for(int i=0;i<edges.size();) {
            double u=edges.get(i).u;
            if(u-position>tolerance && gaps.maximum()>tolerance)return false;
            do { var edge=edges.get(i++);gaps.change(edge.low,edge.high,edge.delta); } while(i<edges.size() && edges.get(i).u==u);
            position=u;
        }
        return 1-position<=tolerance || gaps.maximum()<=tolerance;
    }

    private static final class Gaps {
        private final double[] coordinates,prefix,suffix,gap;
        private final int[] count;
        private final boolean[] empty;
        private Gaps(double[] coordinates) {
            this.coordinates=coordinates;int size=Math.multiplyExact(coordinates.length,4);
            prefix=new double[size];suffix=new double[size];gap=new double[size];count=new int[size];empty=new boolean[size];
            initialize(1,0,coordinates.length-1);
        }
        private void initialize(int node,int low,int high) {
            prefix[node]=suffix[node]=gap[node]=coordinates[high]-coordinates[low];empty[node]=true;
            if(high-low>1) { int middle=(low+high)/2;initialize(node*2,low,middle);initialize(node*2+1,middle,high); }
        }
        private double maximum() { return gap[1]; }
        private void change(int from,int to,int delta) { change(1,0,coordinates.length-1,from,to,delta); }
        private void change(int node,int low,int high,int from,int to,int delta) {
            if(from<=low && high<=to)count[node]+=delta;
            else {
                int middle=(low+high)/2;
                if(from<middle)change(node*2,low,middle,from,to,delta);
                if(to>middle)change(node*2+1,middle,high,from,to,delta);
            }
            if(count[node]>0) { prefix[node]=suffix[node]=gap[node]=0;empty[node]=false; }
            else if(high-low==1) { prefix[node]=suffix[node]=gap[node]=coordinates[high]-coordinates[low];empty[node]=true; }
            else {
                int middle=(low+high)/2,left=node*2,right=left+1;
                empty[node]=empty[left] && empty[right];
                prefix[node]=empty[left]?coordinates[middle]-coordinates[low]+prefix[right]:prefix[left];
                suffix[node]=empty[right]?coordinates[high]-coordinates[middle]+suffix[left]:suffix[right];
                gap[node]=Math.max(Math.max(gap[left],gap[right]),suffix[left]+prefix[right]);
            }
        }
    }
}
