package dev.rt_render_experiment.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.rt_render_experiment.contract.ProducerResources;
import dev.rt_render_experiment.contract.SceneInputs;


public final class ProducerRegistry {
    public record Changes(long world, long revision, List<ProducerResources.Geometry> published,
                          List<ProducerResources.Withdrawal> withdrawn) {
        public Changes { published = List.copyOf(published); withdrawn = List.copyOf(withdrawn); }
        public boolean empty() { return published.isEmpty() && withdrawn.isEmpty(); }
    }
    private record Stamp(long generation, long revision) implements Comparable<Stamp> {
        @Override public int compareTo(Stamp other) {
            int order = Long.compare(generation, other.generation);
            return order == 0 ? Long.compare(revision, other.revision) : order;
        }
    }
    private final long world;
    private final Map<SceneInputs.Key, ProducerResources.Geometry> resident = new LinkedHashMap<>();
    private final Map<SceneInputs.Key, Stamp> retired = new LinkedHashMap<>();
    private final Map<SceneInputs.Key, ProducerResources.Geometry> pending = new LinkedHashMap<>();
    private final Map<SceneInputs.Key, ProducerResources.Withdrawal> withdrawals = new LinkedHashMap<>();
    private long revision = 1;

    public ProducerRegistry(long world) {
        if (world <= 0) throw new IllegalArgumentException("Invalid producer world epoch");
        this.world = world;
    }


    public boolean publish(ProducerResources.Geometry geometry) {
        requireWorld(geometry.world());
        Stamp incoming = new Stamp(geometry.generation(), geometry.revision());
        Stamp tombstone = retired.get(geometry.key());
        if (tombstone != null && incoming.compareTo(tombstone) <= 0) return false;
        var before = resident.get(geometry.key());
        if (before != null) {
            int order = incoming.compareTo(new Stamp(before.generation(), before.revision()));
            if (order < 0 || geometry.transformRevision() < before.transformRevision()
                || geometry.materialRevision() < before.materialRevision()) return false;
            if (order == 0 && geometry.transformRevision() == before.transformRevision()
                && geometry.materialRevision() == before.materialRevision()) {
                if (!geometry.equals(before)) throw new IllegalArgumentException("Producer changed facts without advancing a revision");
                return false;
            }
            if (geometry.generation() == before.generation() && !sameAllocation(geometry, before))
                throw new IllegalArgumentException("Producer relocated storage without advancing its generation");
        }
        resident.put(geometry.key(), geometry);
        pending.put(geometry.key(), geometry);

        withdrawals.remove(geometry.key());
        revision = Math.incrementExact(revision);
        return true;
    }

    public boolean withdraw(ProducerResources.Withdrawal removal) {
        requireWorld(removal.world());
        Stamp incoming = new Stamp(removal.generation(), removal.revision());
        var before = resident.get(removal.key());
        if (before != null && incoming.compareTo(new Stamp(before.generation(), before.revision())) < 0) return false;
        Stamp prior = retired.get(removal.key());
        if (prior != null && incoming.compareTo(prior) <= 0) return false;
        retired.put(removal.key(), incoming);
        resident.remove(removal.key()); pending.remove(removal.key()); withdrawals.put(removal.key(), removal);
        revision = Math.incrementExact(revision);
        return true;
    }

    private static boolean sameAllocation(ProducerResources.Geometry a, ProducerResources.Geometry b) {
        return a.vertices().view().equals(b.vertices().view());
    }

    public ProducerResources.Geometry renew(ProducerResources.Geometry geometry,dev.rt_render_experiment.contract.HostExecution.BufferGrant grant) {
        requireWorld(geometry.world());
        if(resident.get(geometry.key())!=geometry || !grant.view().equals(geometry.vertices().view())
            || grant.contentRevision()!=geometry.revision() || !grant.allowed().equals(geometry.vertices().allowed())
            || grant.validThroughRecording()<geometry.vertices().validThroughRecording())
            throw new IllegalArgumentException("Invalid producer lifetime renewal");
        var renewed=geometry.withVertices(grant);resident.put(geometry.key(),renewed);
        pending.replace(geometry.key(),geometry,renewed);
        return renewed;
    }
    private void requireWorld(long candidate) {
        if (candidate != world) throw new IllegalArgumentException("Producer event belongs to another world epoch");
    }

    public List<ProducerResources.Geometry> resident() { return List.copyOf(resident.values()); }
    public ProducerResources.Geometry get(SceneInputs.Key key) { return resident.get(key); }
    public long revision() { return revision; }
    public long world() { return world; }
    public Changes pending() { return new Changes(world, revision, new ArrayList<>(pending.values()), new ArrayList<>(withdrawals.values())); }

    public void acknowledge(Changes captured) {
        requireWorld(captured.world());
        for (var geometry : captured.published()) pending.remove(geometry.key(), geometry);
        for (var removal : captured.withdrawn()) withdrawals.remove(removal.key(), removal);
    }
}
