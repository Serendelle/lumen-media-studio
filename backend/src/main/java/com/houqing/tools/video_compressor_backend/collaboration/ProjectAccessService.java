package com.houqing.tools.video_compressor_backend.collaboration;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Project grants deliberately do not grant access to the owner's personal library or anonymous form rows. */
@Service
public class ProjectAccessService {
    private final JdbcTemplate db;
    private final ApplicationEventPublisher publisher;
    public ProjectAccessService(JdbcTemplate db, ApplicationEventPublisher publisher) { this.db=db; this.publisher=publisher; }
    public record Access(String projectId, long userId, long ownerId, String role) {}
    public record Change(String projectId) {}
    public Access require(long uid,String pid) {
        var rows=db.queryForList("select p.owner_id, m.role from spec_projects p left join project_members m on m.project_id=p.id and m.user_id=? where p.id=?",uid,pid);
        if(rows.isEmpty()) throw missing();
        var r=rows.get(0); long owner=((Number)r.get("owner_id")).longValue();
        String role=owner==uid?"OWNER":(String)r.get("role");
        if(role==null) throw missing();
        return new Access(pid,uid,owner,role);
    }
    public Access write(long uid,String pid) { Access a=require(uid,pid); if(!Set.of("OWNER","EDITOR").contains(a.role())) throw denied(); return a; }
    /** A real DB row lock (also works in SQLite); must be called inside the mutation transaction. */
    public Access lock(long uid,String pid) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("project lock needs transaction");
        require(uid,pid);
        db.update("update spec_projects set updated_at=updated_at where id=?",pid);
        return write(uid,pid);
    }
    public Access lockMember(long uid,String pid) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("project lock needs transaction");
        require(uid,pid); db.update("update spec_projects set updated_at=updated_at where id=?",pid); return require(uid,pid);
    }
    public Access owner(long uid,String pid) { Access a=require(uid,pid); if(!a.role().equals("OWNER")) throw denied(); return a; }
    public boolean documentCanRead(long uid,String pid,String docId) {
        Access a; try { a=require(uid,pid); } catch(ApiException e) { return false; }
        var rows=db.queryForList("select visibility,unlinked from project_documents where id=? and project_id=?",docId,pid);
        if(rows.isEmpty() || ((Number)rows.get(0).get("unlinked")).intValue()!=0) return false;
        return a.role().equals("OWNER") || "ALL".equals(rows.get(0).get("visibility")) ||
                db.queryForObject("select count(*) from project_document_members where document_id=? and user_id=?",Integer.class,docId,uid)>0;
    }
    public void requireDocument(long uid,String pid,String docId) { if(!documentCanRead(uid,pid,docId)) throw missing(); }
    public List<Long> memberIds(String pid) {
        return db.queryForList("select owner_id from spec_projects where id=? union select user_id from project_members where project_id=?",Long.class,pid,pid);
    }
    public void activity(Access a,String action,String documentId) {
        String name=db.queryForObject("select display_name from users where id=?",String.class,a.userId());
        db.update("insert into project_activities(id,project_id,actor_id,actor_name,action,document_id,created_at) values(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),a.projectId(),a.userId(),name,action,documentId,System.currentTimeMillis());
        db.update("update spec_projects set updated_at=? where id=?",System.currentTimeMillis(),a.projectId());
        changed(a.projectId());
    }
    public void changed(String pid) {
        Runnable send=()->publisher.publishEvent(new Change(pid));
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit(){send.run();} });
        else send.run();
    }
    public static ApiException missing() { return new ApiException(ErrorCode.NOT_FOUND,"项目或资源不存在，或无权访问"); }
    public static ApiException denied() { return new ApiException(ErrorCode.FORBIDDEN,"当前项目角色无权执行此操作"); }
    public static void invalid(String message) { throw new ApiException(ErrorCode.VALIDATION_FAILED,message); }
    public static int size(int n) { return Math.max(1,Math.min(100,n)); }
    public static int version(com.fasterxml.jackson.databind.JsonNode body) {
        var v=body.path("version");if(!v.isIntegralNumber()||!v.canConvertToInt())invalid("version必须是整数");return v.asInt();
    }
    public static String text(String value,int min,int max,String label) {
        String s=value==null?"":value.trim(); if(s.length()<min||s.length()>max) invalid(label+"需为"+min+"-"+max+"个字"); return s;
    }
}
