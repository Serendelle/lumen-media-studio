package com.houqing.tools.video_compressor_backend.collaboration;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Same SSE transport as task events. Events invalidate state; authenticated APIs return filtered data. */
@Component
public class ProjectEventHub {
    private record Connection(long uid,long expiresAt,SseEmitter emitter) {}
    private final Map<String,CopyOnWriteArrayList<Connection>> subscriptions=new ConcurrentHashMap<>();
    private final ProjectAccessService access;
    public ProjectEventHub(ProjectAccessService access) {this.access=access;}
    public synchronized SseEmitter subscribe(long uid,String pid,long expiresAt) {
        access.require(uid,pid);
        long total=subscriptions.values().stream().mapToLong(List::size).sum();
        long perUser=subscriptions.values().stream().flatMap(Collection::stream).filter(c->c.uid==uid).count();
        if(total>=2048||perUser>=8) throw new com.houqing.tools.video_compressor_backend.common.ApiException(com.houqing.tools.video_compressor_backend.common.ErrorCode.RATE_LIMITED,"实时连接过多，请关闭其他项目标签页");
        var emitter=new SseEmitter(30L*60*1000);var c=new Connection(uid,expiresAt,emitter);
        subscriptions.computeIfAbsent(pid,k->new CopyOnWriteArrayList<>()).add(c);
        emitter.onCompletion(()->remove(pid,c));emitter.onTimeout(()->remove(pid,c));emitter.onError(e->remove(pid,c));
        send(pid,c,"snapshot",Map.of("revision",System.currentTimeMillis()));presence(pid);return emitter;
    }
    @EventListener public void changed(ProjectAccessService.Change event) {
        String pid=event.projectId();
        for(Connection c:subscriptions.getOrDefault(pid,new CopyOnWriteArrayList<>())) send(pid,c,"change",Map.of("revision",System.currentTimeMillis()));
        presence(pid);
    }
    public Set<Long> online(String pid) {
        Set<Long> ids=new TreeSet<>();for(Connection c:subscriptions.getOrDefault(pid,new CopyOnWriteArrayList<>())) if(valid(pid,c)) ids.add(c.uid);return ids;
    }
    private void presence(String pid) {
        var data=Map.of("userIds",online(pid));for(Connection c:subscriptions.getOrDefault(pid,new CopyOnWriteArrayList<>())) send(pid,c,"presence",data);
    }
    private boolean valid(String pid,Connection c) {
        if(c.expiresAt<=System.currentTimeMillis()) return false;
        try {access.require(c.uid,pid);return true;} catch(com.houqing.tools.video_compressor_backend.common.ApiException e){return false;}
    }
    private void send(String pid,Connection c,String event,Object data) {
        if(!valid(pid,c)){remove(pid,c);c.emitter.complete();return;}
        try {c.emitter.send(SseEmitter.event().name(event).data(data));}catch(Exception e){remove(pid,c);c.emitter.complete();}
    }
    private void remove(String pid,Connection c){var list=subscriptions.get(pid);if(list!=null){list.remove(c);if(list.isEmpty())subscriptions.remove(pid,list);}}
    @Scheduled(fixedDelay=15000) public void heartbeat() {
        subscriptions.forEach((pid,list)->{for(Connection c:list) {
            if(!valid(pid,c)){remove(pid,c);c.emitter.complete();continue;}
            try{c.emitter.send(SseEmitter.event().comment("ping"));}catch(Exception e){remove(pid,c);c.emitter.complete();}
        }presence(pid);});
    }
}
