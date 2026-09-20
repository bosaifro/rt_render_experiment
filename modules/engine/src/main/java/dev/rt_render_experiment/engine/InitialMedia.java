package dev.rt_render_experiment.engine;

import java.util.Collection;
import java.util.HashSet;
import dev.rt_render_experiment.contract.SceneInputs;


final class InitialMedia {



    static final class ClipBoundaries {
        private final java.util.Map<SceneInputs.Key,SceneCompiler.Compiled> geometry;
        private final int primitives;
        ClipBoundaries(java.util.List<SceneCompiler.Compiled> geometry,int primitives) {
            var values=new java.util.HashMap<SceneInputs.Key,SceneCompiler.Compiled>();
            for(var mesh:geometry)values.put(mesh.input().key(),mesh);
            this.geometry=java.util.Map.copyOf(values);this.primitives=primitives;
        }
        int primitives() { return primitives; }
        long logicalBytes() { return 4L+16L*geometry.size(); }
        void requireGeometry(Collection<SceneCompiler.Compiled> candidate) {
            ClipGeometry.requireSources(candidate);
            int found=0;
            for(var mesh:candidate) {
                boolean identified=!mesh.clipContacts().isEmpty();
                for(var part:mesh.parts()) {
                    if(part.surface().boundary()!=SceneInputs.Boundary.UNQUALIFIED)identified=true;
                    else if(MediumProfiles.potential(part.surface()))throw new IllegalArgumentException("Unclassified volume invalidates clipped boundary domain");
                }
                if(identified) {
                    if(geometry.get(mesh.input().key())!=mesh)throw new IllegalArgumentException("Clipped boundary classification belongs to another geometry generation");
                    found++;
                }
            }
            if(found!=geometry.size())throw new IllegalArgumentException("Clipped boundary classification has missing geometry");
        }
    }
    private InitialMedia() {}
    static void validate(RenderFrame frame,long world,long serial,Collection<SceneCompiler.Compiled> geometry) {
        var supplied=frame.initialMedia();if(supplied.isEmpty())return;var origin=supplied.orElseThrow();
        if(origin.world()!=world || origin.sceneRevision()!=serial)throw new IllegalArgumentException("Initial medium belongs to a different scene publication");
        var eye=frame.eye();
        if(Math.abs(eye.x()-origin.position().x())>1e-6 || Math.abs(eye.y()-origin.position().y())>1e-6 || Math.abs(eye.z()-origin.position().z())>1e-6)
            throw new IllegalArgumentException("Initial medium was classified at a different eye");
        float maximum=0;var jitter=frame.projectionJitter();
        for(float x:new float[]{-1,1})for(float y:new float[]{-1,1})maximum=Math.max(maximum,frame.camera().ray(x-jitter.x(),y-jitter.y()).minimum());
        if(frame.clippedMediumPrimitives()==0 && origin.clearance()<=maximum*(1+1e-4f)+1e-6f)
            throw new IllegalArgumentException("Initial medium clearance does not cover near clipping; a per-ray interface prefix is required");
        if(frame.clippedBoundaries()!=null)frame.clippedBoundaries().requireGeometry(geometry);
        var missing=new HashSet<Long>();for(var enclosure:origin.enclosures())missing.add(enclosure.volume());
        for(var mesh:geometry)for(var part:mesh.parts())if(part.surface().boundary()!=SceneInputs.Boundary.UNQUALIFIED)
            missing.remove(part.surface().medium());
        for(var mesh:geometry)for(var contact:mesh.clipContacts())missing.remove(contact.medium());
        if(!missing.isEmpty())throw new IllegalArgumentException("Initial medium references absent identified volumes: "+missing);
    }
}
