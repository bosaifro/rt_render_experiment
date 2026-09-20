package dev.rt_render_experiment.engine;

import dev.rt_render_experiment.engine.abi.R2Abi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.Participation;
import dev.rt_render_experiment.contract.SceneInputs;


final class OpaqueContacts {
    record Request(SceneInputs.Geometry reference,List<ConvexVolume.Point> points) {
        Request { points=List.copyOf(points);if(points.size()!=3 && points.size()!=4)throw new IllegalArgumentException("Contact requires a complete triangle or quad"); }
    }

    record Solid(SceneCompiler.Compiled mesh,SceneCompiler.Part part,List<SceneInputs.Vec3> clip,List<ClipGeometry.Support> supports) {
        Solid(SceneCompiler.Compiled mesh,SceneCompiler.Part part,List<SceneInputs.Vec3> clip) { this(mesh,part,clip,List.of()); }
        Solid { clip=List.copyOf(clip);supports=List.copyOf(supports); }
        int primitiveCount() { return supports.isEmpty()?1:supports.size(); }
    }
    private record Linear(List<Float> values) {
        static Linear of(SceneInputs.Transform transform) {
            var values=new ArrayList<Float>();
            for(int row=0;row<3;row++)for(int column=0;column<3;column++) { float value=transform.get(row,column);values.add(value==0?0:value); }
            return new Linear(List.copyOf(values));
        }
    }
    private record FaceKey(List<ConvexVolume.Point> points) {
        static FaceKey of(List<ConvexVolume.Point> points) { return new FaceKey(points.stream().sorted(ConvexVolume::compare).toList()); }
    }
    private static final class Face {
        final Request request;final List<ConvexVolume.Point> points;final double[] bounds;
        final List<Solid> solids=new ArrayList<>();final List<List<ConvexVolume.Point>> tiles=new ArrayList<>();
        final PlanarContactCoverage coverage;
        final Map<Integer,ExactContactCover.Polygon> crossings=new HashMap<>();
        ExactContactCover exact;
        Face(Request request,List<ConvexVolume.Point> points,ConvexVolume.Budget budget) {
            this.request=request;this.points=points;bounds=bounds(points);
            try { coverage=new PlanarContactCoverage(points,budget); }
            catch(IllegalArgumentException failure) { throw new IllegalArgumentException(failure.getMessage()+"; interface="+points+"; reference="+describe(request.reference),failure); }
        }
        ExactContactCover exact(ConvexVolume.Budget budget) { if(exact==null)exact=new ExactContactCover(points,budget);return exact; }
    }

    private static final class Index {
        final double[] bounds;final Index left,right;final List<Face> faces;
        Index(List<Face> input,ConvexVolume.Budget budget) {
            bounds=new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            for(var face:input) { budget.spend(1);for(int axis=0;axis<3;axis++) { bounds[axis]=Math.min(bounds[axis],face.bounds[axis]);bounds[axis+3]=Math.max(bounds[axis+3],face.bounds[axis+3]); } }
            if(input.size()<=8) { faces=List.copyOf(input);left=null;right=null; }
            else {
                int selected=0;for(int axis=1;axis<3;axis++)if(bounds[axis+3]-bounds[axis]>bounds[selected+3]-bounds[selected])selected=axis;
                final int axis=selected;var ordered=new ArrayList<>(input);
                ordered.sort(java.util.Comparator.comparingDouble(f->f.bounds[axis]*.5+f.bounds[axis+3]*.5));
                int middle=ordered.size()/2;left=new Index(ordered.subList(0,middle),budget);right=new Index(ordered.subList(middle,ordered.size()),budget);faces=List.of();
            }
        }
        void find(double[] candidate,List<Face> result,ConvexVolume.Budget budget) {
            budget.spend(1);if(!overlaps(bounds,candidate))return;
            if(left!=null) { left.find(candidate,result,budget);right.find(candidate,result,budget); }
            else for(var face:faces) { budget.spend(1);if(overlaps(face.bounds,candidate))result.add(face); }
        }
    }
    private static final class Space {
        final SceneInputs.Geometry reference;
        final Map<FaceKey,Face> required=new HashMap<>();
        Space(SceneInputs.Geometry reference) { this.reference=reference; }
    }
    static Map<Request,List<Solid>> match(List<Request> requests,List<SceneCompiler.Compiled> geometry,int partLimit,ConvexVolume.Budget budget) {
        if(requests.isEmpty())return Map.of();
        var spaces=new HashMap<Linear,Space>();var found=new HashMap<Request,List<Solid>>();
        for(var request:requests) {
            var space=spaces.computeIfAbsent(Linear.of(request.reference.current()),k->new Space(request.reference));
            var points=request.points.stream().map(p->ConvexVolume.point(space.reference,request.reference,p.x(),p.y(),p.z())).toList();
            if(space.required.put(FaceKey.of(points),new Face(request,points,budget))!=null)
                throw unsupported("A contact interface has multiple region owners");
        }
        var indices=new HashMap<Space,Index>();for(var space:spaces.values())indices.put(space,new Index(new ArrayList<>(space.required.values()),budget));
        int inspected=0;
        for(var mesh:geometry) {
            var space=spaces.get(Linear.of(mesh.input().current()));if(space==null)continue;
            for(var part:mesh.parts()) {
                if(++inspected>partLimit)throw new IllegalStateException("Opaque contact source-part preparation pressure");
                budget.spend(1);



                if(part.surface().coverage()!=SceneInputs.Coverage.OPAQUE || mesh.input().participation().rayMask()!=Participation.WORLD
                    || !mesh.input().participation().primaryVisible())continue;
                var points=new ArrayList<ConvexVolume.Point>();var positions=mesh.positions();
                for(int i=0;i<part.vertices();i++) {
                    int offset=(part.firstVertex()+i)*SceneCompiler.POSITION_STRIDE;
                    points.add(ConvexVolume.point(space.reference,mesh.input(),positions.getFloat(offset),positions.getFloat(offset+4),positions.getFloat(offset+8)));
                }
                var candidates=new ArrayList<Face>();indices.get(space).find(bounds(points),candidates,budget);
                for(var face:candidates) {
                    String operation="whole partner";
                    try {
                        boolean whole=face.coverage.contains(points,budget);List<SceneInputs.Vec3> clip=List.of();
                        if(!whole) {
                            var plane=new ConvexVolume.Triangle(points.get(0),points.get(1),points.get(2));boolean coplanar=true;
                            for(var p:points)if(ConvexVolume.side(plane,p,budget)!=0)coplanar=false;
                            for(var p:face.points)if(ConvexVolume.side(plane,p,budget)!=0)coplanar=false;
                            if(!coplanar)continue;
                            operation="containing partner";
                            boolean contained=contains(bounds(points),face.bounds) && new PlanarContactCoverage(points,budget).contains(face.points,budget);
                            if(!contained) {
                                operation="crossing partner";
                                var crossing=face.exact(budget).intersection(points,budget);if(crossing==null)continue;
                                face.crossings.put(face.solids.size(),crossing);
                            }



                            if(contained) {
                                clip=localPoints(mesh.input(),space.reference,face.points.reversed());
                                if(clip==null) {



                                    face.crossings.put(face.solids.size(),face.exact(budget).intersection(points,budget));clip=List.of();
                                }
                            }
                        }
                        operation="surface qualification";
                        if(!MediumProfiles.opaqueContact(part.surface()))
                            throw unsupported("A matching contact lacks an opaque response or full physical participation");
                        if(!flatNormals(mesh,part))throw unsupported("Opaque contact authored normals are unsupported");
                        face.solids.add(new Solid(mesh,part,clip));face.tiles.add(whole?List.copyOf(points):List.copyOf(face.points.reversed()));
                    } catch(IllegalArgumentException failure) {


                        throw new IllegalArgumentException(failure.getMessage()+"; operation="+operation+"; interface="+face.points
                            +"; candidate="+points+"; part="+part.key()+"/"+part.ordinal()+"; material="+part.surface().material()
                            +"; coverage="+part.surface().coverage()+"; sided="+part.surface().doubleSided()
                            +"; source="+describe(mesh.input())+"; space="+describe(space.reference)+"; reference="+face.request.reference.key(),failure);
                    }
                }
            }
        }
        for(var space:spaces.values())for(var face:space.required.values()) {
            if(!face.crossings.isEmpty()) {
                var pieces=new ArrayList<ExactContactCover.Polygon>();
                for(int i=0;i<face.solids.size();i++)pieces.add(face.crossings.containsKey(i)?face.crossings.get(i):ExactContactCover.whole(face.tiles.get(i)));
                try { face.exact(budget).certify(pieces,budget); }
                catch(IllegalArgumentException failure) { throw coverFailure(failure,face,space); }
                var supports=face.solids.stream().map(s->new ClipGeometry.Support(s.mesh,s.part)).toList();
                for(var support:supports)requireOwner(support.mesh(),support.part());


                var anchors=face.solids.stream().map(Solid::mesh).distinct().sorted(java.util.Comparator.comparingLong(SceneCompiler.Compiled::renderBytes).thenComparing(m->m.input().key())).toList();
                Solid selected=null;
                for(var anchor:anchors) {
                    var local=localPoints(anchor.input(),space.reference,face.points.reversed());if(local==null)continue;
                    var owner=supports.stream().filter(s->s.mesh()==anchor).findFirst().orElseThrow().part();selected=new Solid(anchor,owner,local,supports);break;
                }
                if(selected==null) {




                    var anchor=geometry.stream().filter(m->m.input()==face.request.reference).findFirst().orElse(null);
                    if(anchor!=null) {
                        requireAnchor(anchor);var local=localPoints(anchor.input(),space.reference,face.points.reversed());
                        if(local!=null)selected=new Solid(anchor,supports.getFirst().part(),local,supports);
                    }
                }
                if(selected==null)throw unsupported("Contact cannot preserve interface corners in a supported trace space");
                found.put(face.request,List.of(selected));continue;
            }
            if(face.tiles.size()!=1 || !opposite(face.points,face.tiles.getFirst())) {
                try { face.coverage.certify(face.tiles,budget); }
                catch(IllegalArgumentException failure) { throw coverFailure(failure,face,space); }
            }
            found.put(face.request,List.copyOf(face.solids));
        }
        return Map.copyOf(found);
    }
    private static List<SceneInputs.Vec3> localPoints(SceneInputs.Geometry target,SceneInputs.Geometry reference,List<ConvexVolume.Point> points) {
        var local=new ArrayList<SceneInputs.Vec3>();
        for(var p:points) {
            var q=ConvexVolume.point(target,reference,p.x(),p.y(),p.z());
            if(q.x()!=(float)q.x() || q.y()!=(float)q.y() || q.z()!=(float)q.z())return null;
            local.add(new SceneInputs.Vec3((float)q.x(),(float)q.y(),(float)q.z()));
        }
        return List.copyOf(local);
    }
    private static String describe(SceneInputs.Geometry source) {
        return source.key()+" "+source.revision()+" "+source.origin()+" "+source.motion()+" "+source.participation()
            +" "+java.util.Arrays.toString(source.current().rows());
    }
    private static IllegalArgumentException coverFailure(IllegalArgumentException failure,Face face,Space space) {
        var detail=new StringBuilder(failure.getMessage()).append("; interface=").append(face.points)
            .append("; space=").append(describe(space.reference)).append("; contributors=").append(face.solids.size());


        for(int i=0;i<Math.min(face.solids.size(),8);i++) {
            var solid=face.solids.get(i);var points=new ArrayList<ConvexVolume.Point>();var positions=solid.mesh.positions();
            for(int c=0;c<solid.part.vertices();c++) {
                int at=(solid.part.firstVertex()+c)*SceneCompiler.POSITION_STRIDE;
                points.add(ConvexVolume.point(space.reference,solid.mesh.input(),positions.getFloat(at),positions.getFloat(at+4),positions.getFloat(at+8)));
            }
            detail.append("; contributor=").append(describe(solid.mesh.input())).append(" part=").append(solid.part.key()).append('/').append(solid.part.ordinal())
                .append(" material=").append(solid.part.surface().material()).append(" points=").append(points);
        }
        if(face.solids.size()>8)detail.append("; omitted=").append(face.solids.size()-8);
        return new IllegalArgumentException(detail.toString(),failure);
    }
    private static double[] bounds(List<ConvexVolume.Point> points) {
        var result=new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(var point:points)for(int axis=0;axis<3;axis++) { result[axis]=Math.min(result[axis],point.axis(axis));result[axis+3]=Math.max(result[axis+3],point.axis(axis)); }
        return result;
    }
    private static boolean contains(double[] outer,double[] inner) {
        for(int axis=0;axis<3;axis++)if(inner[axis]<outer[axis] || inner[axis+3]>outer[axis+3])return false;return true;
    }
    private static boolean overlaps(double[] a,double[] b) {
        for(int axis=0;axis<3;axis++)if(a[axis]>b[axis+3] || b[axis]>a[axis+3])return false;return true;
    }
    static void requireClip(SceneCompiler.Compiled mesh,ClipGeometry.Contact contact,ConvexVolume.Budget budget) {
        var part=contact.owner();requireAnchor(mesh);
        if(!contact.supports().isEmpty()) {
            if(contact.supports().stream().noneMatch(s->s.part().equals(part)))throw unsupported("Clipping contact has no actual opaque owner");
            var goal=contact.corners().reversed().stream().map(p->new ConvexVolume.Point(p.x(),p.y(),p.z())).toList();
            var cover=new ExactContactCover(goal,budget);var pieces=new ArrayList<ExactContactCover.Polygon>();
            for(var support:contact.supports()) {
                if(support.mesh().input().revision().world()!=mesh.input().revision().world())throw unsupported("Clipping contact support belongs to another world");
                requireOwner(support.mesh(),support.part());var positions=support.mesh().positions();var points=new ArrayList<ConvexVolume.Point>();
                for(int i=0;i<support.part().vertices();i++) {
                    int offset=(support.part().firstVertex()+i)*SceneCompiler.POSITION_STRIDE;
                    points.add(ConvexVolume.point(mesh.input(),support.mesh().input(),positions.getFloat(offset),positions.getFloat(offset+4),positions.getFloat(offset+8)));
                }
                var piece=cover.intersection(points,budget);if(piece==null)throw unsupported("Crossing contact retains an unrelated support");pieces.add(piece);
            }
            cover.certify(pieces,budget);return;
        }
        requireOwner(mesh,part);
        var positions=mesh.positions();var points=new ArrayList<ConvexVolume.Point>();
        for(int i=0;i<part.vertices();i++) {
            int offset=(part.firstVertex()+i)*SceneCompiler.POSITION_STRIDE;
            points.add(new ConvexVolume.Point(positions.getFloat(offset),positions.getFloat(offset+4),positions.getFloat(offset+8)));
        }
        var patch=contact.corners().reversed().stream().map(p->new ConvexVolume.Point(p.x(),p.y(),p.z())).toList();
        if(!new PlanarContactCoverage(points,budget).contains(patch,budget))throw unsupported("Partial contact extends outside its opaque owner");
    }
    private static void requireAnchor(SceneCompiler.Compiled mesh) {
        if(mesh.triangleCount()==0 || mesh.input().participation().rayMask()!=Participation.WORLD || !mesh.input().participation().primaryVisible())
            throw unsupported("Clipping contact anchor lacks physical world-scene membership");
    }
    private static void requireOwner(SceneCompiler.Compiled mesh,SceneCompiler.Part part) {
        if(!mesh.parts().contains(part) || part.surface().boundary()!=SceneInputs.Boundary.UNQUALIFIED || !MediumProfiles.opaqueContact(part.surface()) || !flatNormals(mesh,part)
            || mesh.input().participation().rayMask()!=Participation.WORLD || !mesh.input().participation().primaryVisible())
            throw unsupported("Partial contact lacks an actual opaque owner in its prepared pose");
    }
    static boolean opposite(List<ConvexVolume.Point> a,List<ConvexVolume.Point> b) {
        if(a.size()!=b.size())return false;
        int start=b.indexOf(a.getFirst());if(start<0)return false;
        for(int i=0;i<a.size();i++)if(!a.get(i).equals(b.get((start-i+a.size())%a.size())))return false;
        return true;
    }

    static boolean flatNormals(SceneCompiler.Compiled mesh,SceneCompiler.Part part) {
        var positions=mesh.positions();var corners=mesh.corners();double[][] p=new double[3][3];
        for(int i=0;i<3;i++)for(int axis=0;axis<3;axis++)p[i][axis]=positions.getFloat((part.firstVertex()+i)*SceneCompiler.POSITION_STRIDE+axis*4);
        double[] a={p[1][0]-p[0][0],p[1][1]-p[0][1],p[1][2]-p[0][2]},b={p[2][0]-p[0][0],p[2][1]-p[0][1],p[2][2]-p[0][2]};
        double[] n={a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};
        if(n[0]==0 && n[1]==0 && n[2]==0)return false;
        for(int i=0;i<part.vertices();i++) {
            int offset=(part.firstVertex()+i)*SceneCompiler.CORNER_STRIDE+dev.rt_render_experiment.engine.abi.R2Abi.CornerRecord.NORMAL;
            double x=corners.getFloat(offset),y=corners.getFloat(offset+4),z=corners.getFloat(offset+8);
            if(x*n[1]!=y*n[0] || y*n[2]!=z*n[1] || z*n[0]!=x*n[2])return false;
        }
        return true;
    }
    private static IllegalArgumentException unsupported(String reason) { return new IllegalArgumentException(reason); }
}
