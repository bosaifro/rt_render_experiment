package dev.rt_render_experiment.engine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.rt_render_experiment.contract.SceneInputs;


final class SourcePublicationGroups {
    record Group(Map<SceneInputs.Key,SceneInputs.Revision> required) {
        Group { required=Map.copyOf(required); }
        boolean ready(Map<SceneInputs.Key,SceneStore.CompiledSource> sources) {
            for(var member:required.entrySet()) {
                var source=sources.get(member.getKey());
                if(source==null || !source.request().expected().equals(member.getValue()))return false;
            }
            return true;
        }
    }
    private final Set<Group> groups=new LinkedHashSet<>();
    private final Map<SceneInputs.Key,Group> members=new HashMap<>();
    private final Set<Group> sealed=new LinkedHashSet<>();
    private final Map<SceneInputs.Key,Group> sealedMembers=new HashMap<>();
    private long declarations,publications,seals,sealedPublications,revokedSeals;
    void relate(SceneInputs.RelatedSources relation) {
        var combined=new LinkedHashMap<SceneInputs.Key,SceneInputs.Revision>();var previous=new LinkedHashSet<Group>();
        for(var key:relation.required().keySet()) {
            var group=members.get(key);if(group!=null && previous.add(group))combined.putAll(group.required());
        }
        combined.putAll(relation.required());for(var group:previous)remove(group);
        add(new Group(combined));declarations++;
    }
    void expected(SceneInputs.Key key,SceneInputs.Revision revision) {
        var fixed=sealedMembers.get(key);
        if(fixed!=null) {
            var before=fixed.required().get(key);
            if(before.world()!=revision.world() || before.resources()!=revision.resources())revoke(key);
        }
        var group=members.get(key);if(group==null || revision.equals(group.required().get(key)))return;
        var next=new LinkedHashMap<>(group.required());next.put(key,revision);remove(group);add(new Group(next));
    }

    void removed(SceneInputs.Key key) {
        revoke(key);
        var group=members.get(key);if(group==null)return;
        var next=new LinkedHashMap<>(group.required());next.remove(key);remove(group);if(!next.isEmpty())add(new Group(next));
    }
    private void add(Group group) { groups.add(group);for(var key:group.required().keySet())members.put(key,group); }
    private void remove(Group group) { groups.remove(group);for(var key:group.required().keySet())members.remove(key,group); }

    void seal(Map<SceneInputs.Key,SceneStore.CompiledSource> compiled,Map<SceneInputs.Key,SceneInputs.PreparationRequest> prepared) {
        for(var group:groups) {
            boolean complete=true;
            for(var member:group.required().entrySet()) {
                if(sealedMembers.containsKey(member.getKey())) { complete=false;break; }
                var source=compiled.get(member.getKey());var request=prepared.get(member.getKey());
                if(!(source!=null && source.request().expected().equals(member.getValue()))
                    && !(request!=null && request.expected().equals(member.getValue()))) { complete=false;break; }
            }
            if(complete) {
                sealed.add(group);for(var key:group.required().keySet())sealedMembers.put(key,group);seals++;
            }
        }
    }
    void revoke(SceneInputs.Key key) {
        var group=sealedMembers.get(key);if(group==null)return;
        release(group);revokedSeals++;
    }
    private void release(Group group) { sealed.remove(group);for(var key:group.required().keySet())sealedMembers.remove(key,group); }
    void published(List<Group> completed,List<Group> completedSeals) {
        var distinct=new LinkedHashSet<Group>();
        for(var group:completed) { remove(group);distinct.add(group); }
        for(var group:completedSeals) { release(group);sealedPublications++;distinct.add(group); }
        publications+=distinct.size();
    }
    List<Group> pending() { return List.copyOf(groups); }
    List<Group> sealed() { return List.copyOf(sealed); }
    boolean contains(SceneInputs.Key key) { return members.containsKey(key); }
    boolean locked(SceneInputs.Key key) { return sealedMembers.containsKey(key); }
    SceneStore.GroupStatistics statistics() {
        var vectors=new LinkedHashSet<>(groups);vectors.addAll(sealed);
        long bytes=vectors.stream().mapToLong(group->72L*group.required().size()).sum();
        return new SceneStore.GroupStatistics(groups.size(),members.size(),bytes,declarations,publications,sealed.size(),sealedMembers.size(),seals,sealedPublications,revokedSeals);
    }
    void clear() { groups.clear();members.clear();sealed.clear();sealedMembers.clear(); }
}
