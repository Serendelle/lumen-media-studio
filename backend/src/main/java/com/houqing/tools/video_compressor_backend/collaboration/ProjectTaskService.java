package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.houqing.tools.video_compressor_backend.common.*;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Reuses TaskEngine; project authorization never impersonates a member as the owner's personal account. */
@Service
public class ProjectTaskService {
    private static final Set<String> OPERATIONS=Set.of("video.compress","video.convert","audio.extract","image.process","pdf.process","metadata.scrub","document.convert");
    private final ProjectAccessService access;private final ProjectFileService files;private final TaskEngine engine;private final StorageService storage;
    private final JdbcTemplate db;private final ObjectMapper mapper;private final TransactionTemplate tx;
    public ProjectTaskService(ProjectAccessService access,ProjectFileService files,@Lazy TaskEngine engine,StorageService storage,JdbcTemplate db,ObjectMapper mapper,PlatformTransactionManager manager){this.access=access;this.files=files;this.engine=engine;this.storage=storage;this.db=db;this.mapper=mapper;this.tx=new TransactionTemplate(manager);}
    public Map<String,Object> submit(long uid,String pid,String versionId,JsonNode body){
        access.write(uid,pid);String type=body.path("type").asText();if(!OPERATIONS.contains(type))ProjectAccessService.invalid("该任务类型暂未开放项目处理");
        var source=files.raw(uid,pid,versionId);JsonNode params=body.path("params");if(!params.isObject())ProjectAccessService.invalid("请填写任务参数");
        return start(uid,pid,versionId,type,params,List.of((String)source.get("file_id")),key(body));
    }
    public Map<String,Object> deliver(long uid,String pid,JsonNode body){
        var a=access.owner(uid,pid);var latest=files.allLatest(uid,pid);if(latest.isEmpty())ProjectAccessService.invalid("项目还没有可交付文件");
        if(latest.stream().anyMatch(v->!v.get("reviewStatus").equals("APPROVED")))ProjectAccessService.invalid("请先审核通过所有最新材料");
        ObjectNode params=mapper.createObjectNode();params.put("projectId",pid);ArrayNode entries=params.putArray("entries");List<String> inputs=new ArrayList<>();
        for(var v:latest){String vid=v.get("id").toString();var raw=files.raw(uid,pid,vid);String fid=raw.get("file_id").toString();inputs.add(fid);entries.addObject().put("versionId",vid).put("fileId",fid).put("sha256",v.get("sha256").toString());}
        return start(a.userId(),pid,null,"project.deliver",params,inputs,key(body));
    }
    private Map<String,Object> start(long uid,String pid,String versionId,String type,JsonNode params,List<String> inputs,String key){
        // Keep the engine's documented no-transaction submission: a queued worker must see committed task rows.
        // Check grants in short transactions before and after submission. If revoked between them, cancel this
        // unassociated task; it never becomes a shared result or grants access to the owner's library.
        var a=tx.execute(s->{var locked=access.lock(uid,pid);validateSources(uid,pid,versionId,type,params);return locked;});
        var previous=db.queryForList("select task_id from project_tasks where project_id=? and request_key=?",pid,key);
        if(!previous.isEmpty()){String tid=previous.get(0).get("task_id").toString();var replay=task(uid,pid,tid);checkIntent(engine.require(a.ownerId(),tid),type,params,inputs);return replay;}
        TaskEntity t=engine.submit(a.ownerId(),type,params,inputs,"PROJECT", "project:"+pid+":"+key);
        checkIntent(t,type,params,inputs);
        try{return tx.execute(s->{var locked=access.lock(uid,pid);validateSources(uid,pid,versionId,type,params);
            var existing=db.queryForList("select task_id from project_tasks where project_id=? and request_key=?",pid,key);if(!existing.isEmpty()){String tid=existing.get(0).get("task_id").toString();var replay=task(uid,pid,tid);checkIntent(engine.require(locked.ownerId(),tid),type,params,inputs);return replay;}
            db.update("insert into project_tasks(task_id,project_id,file_version_id,created_by,last_status,created_at,request_key) values(?,?,?,?,?,?,?)",t.getId(),pid,versionId,uid,t.getStatus(),t.getCreatedAt(),key);
            access.activity(locked,"TASK_CREATED",versionId==null?null:(String)files.raw(uid,pid,versionId).get("document_id"));return view(t,null);
        });}catch(RuntimeException e){if(db.queryForObject("select count(*) from project_tasks where task_id=?",Integer.class,t.getId())==0)try{engine.cancel(a.ownerId(),t.getId());}catch(ApiException ignored){}throw e;}
    }
    private void checkIntent(TaskEntity t,String type,JsonNode params,List<String> inputs){
        try{if(!type.equals(t.getType())||!mapper.readTree(t.getParamsJson()).equals(params)||!mapper.readTree(t.getInputFiles()).equals(mapper.valueToTree(inputs)))throw new ApiException(ErrorCode.VERSION_CONFLICT,"此任务标识已用于其他请求，请使用新的标识");}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ApiException(ErrorCode.VERSION_CONFLICT,"原任务参数无法核对，请使用新的标识");}
    }
    private void validateSources(long uid,String pid,String versionId,String type,JsonNode params){
        if(type.equals("project.deliver")){access.owner(uid,pid);Set<String> snapshot=new HashSet<>();for(JsonNode entry:params.path("entries")){snapshot.add(entry.path("versionId").asText());var r=files.raw(uid,pid,entry.path("versionId").asText());if(!"APPROVED".equals(r.get("review_status")))ProjectAccessService.invalid("材料审核状态已变化，请重新检查");}
            Set<String> latest=new HashSet<>();for(var v:files.allLatest(uid,pid))latest.add(v.get("id").toString());if(!snapshot.equals(latest))throw new ApiException(ErrorCode.VERSION_CONFLICT,"最新文件已变化，请重新生成交付快照");}
        else files.raw(uid,pid,versionId);
    }
    private String key(JsonNode b){String k=b.path("idempotencyKey").asText();if(!k.matches("[A-Za-z0-9_-]{8,100}"))ProjectAccessService.invalid("请提供8-100位任务重试标识");return k;}
    public List<Map<String,Object>> list(long uid,String pid){access.require(uid,pid);return db.queryForList("select * from project_tasks where project_id=? order by created_at desc limit 100",pid).stream().filter(r->canRead(uid,pid,r)).map(r->view(engine.require(access.require(uid,pid).ownerId(),r.get("task_id").toString()),(String)r.get("output_version_id"))).toList();}
    private Map<String,Object> task(long uid,String pid,String id){var rows=db.queryForList("select * from project_tasks where project_id=? and task_id=?",pid,id);if(rows.isEmpty()||!canRead(uid,pid,rows.get(0)))throw ProjectAccessService.missing();return view(engine.require(access.require(uid,pid).ownerId(),id),(String)rows.get(0).get("output_version_id"));}
    private boolean canRead(long uid,String pid,Map<String,Object> r){try{var a=access.require(uid,pid);if(r.get("file_version_id")!=null)files.raw(uid,pid,r.get("file_version_id").toString());else {TaskEntity t=engine.require(a.ownerId(),r.get("task_id").toString());JsonNode params=mapper.readTree(t.getParamsJson());for(JsonNode e:params.path("entries"))files.raw(uid,pid,e.path("versionId").asText());}return true;}catch(Exception e){return false;}}
    private Map<String,Object> view(TaskEntity t,String output){
        var errors=db.queryForList("select output_error from project_tasks where task_id=?",String.class,t.getId());String error=errors.isEmpty()?null:errors.get(0);
        Map<String,Object> v=new LinkedHashMap<>();v.put("id",t.getId());v.put("type",t.getType());v.put("status",error==null?t.getStatus():"OUTPUT_UNAVAILABLE");v.put("processingStatus",t.getStatus());v.put("progress",t.getProgress());v.put("message",error==null?t.getMessage():"处理已完成，但项目结果无法登记："+error);v.put("errorCode",error==null?t.getErrorCode():"PROJECT_OUTPUT_UNAVAILABLE");v.put("outputVersionId",output);v.put("createdAt",t.getCreatedAt());
        if(output!=null){var names=db.queryForList("select m.original_name from project_file_versions v join media_files m on m.id=v.file_id where v.id=?",String.class,output);if(!names.isEmpty())v.put("outputFilename",names.get(0));}return v;
    }
    /** Persist task transitions and attach verified output; an offline client sees the same DB state on reconnect. */
    @Scheduled(fixedDelay=1000) public void synchronizeTasks(){
        for(var r:db.queryForList("select * from project_tasks where last_status in('QUEUED','RUNNING') or (last_status='SUCCEEDED' and output_version_id is null) order by created_at limit 200")){
            try{sync(r);}catch(ApiException e){outputFailed(r,e.getMessage());}
            catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("project task synchronization deferred: {}",e.getClass().getSimpleName());}
        }
    }
    private void outputFailed(Map<String,Object> row,String reason){
        String pid=row.get("project_id").toString(),tid=row.get("task_id").toString();
        var owners=db.queryForList("select owner_id from spec_projects where id=?",Long.class,pid);if(owners.isEmpty())return;
        if(!"SUCCEEDED".equals(engine.require(owners.get(0),tid).getStatus()))return;
        tx.executeWithoutResult(s->{var a=access.lock(owners.get(0),pid);db.update("update project_tasks set last_status='OUTPUT_UNAVAILABLE',output_error=? where task_id=? and output_version_id is null",reason.length()>1000?reason.substring(0,1000):reason,tid);access.activity(a,"TASK_OUTPUT_UNAVAILABLE",null);});
    }
    private void sync(Map<String,Object> row){String pid=row.get("project_id").toString(),tid=row.get("task_id").toString();
        var owners=db.queryForList("select owner_id from spec_projects where id=?",Long.class,pid);if(owners.isEmpty())return;long owner=owners.get(0);TaskEntity t=engine.require(owner,tid);
        if(t.getStatus().equals(row.get("last_status"))&&t.getProgress()==((Number)row.get("last_progress")).intValue()&&!(t.getStatus().equals("SUCCEEDED")&&row.get("output_version_id")==null))return;
        tx.executeWithoutResult(s->{var a=access.lock(owner,pid);var fresh=db.queryForMap("select * from project_tasks where task_id=?",tid);String doc=null;String sourceId=(String)fresh.get("file_version_id");
            if(sourceId!=null){var source=db.queryForList("select document_id from project_file_versions where id=?",sourceId);if(!source.isEmpty())doc=(String)source.get(0).get("document_id");}
            if("SUCCEEDED".equals(t.getStatus())&&t.getOutputFile()!=null&&fresh.get("output_version_id")==null){
                MediaFile f=storage.require(owner,t.getOutputFile());boolean delivery=t.getType().equals("project.deliver");
                // An unlinked document remains inaccessible; its output must not become an unrestricted file.
                if(doc!=null&&!access.documentCanRead(owner,pid,doc)){db.update("update project_tasks set last_status='OUTPUT_UNAVAILABLE',output_error='来源文档已解除关联' where task_id=?",tid);access.changed(pid);return;}
                var output=files.register(a,f,null,doc,ProjectFileService.sha256(storage.pathOf(f)),delivery?"DELIVERY_READY":"PROCESSING_READY");String oid=output.get("id").toString();
                if(delivery)try{for(JsonNode e:mapper.readTree(t.getParamsJson()).path("entries"))db.update("insert into project_bundle_sources(output_version_id,source_version_id) values(?,?)",oid,e.path("versionId").asText());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ApiException(ErrorCode.OUTPUT_INVALID);}
                db.update("update project_tasks set output_version_id=? where task_id=?",oid,tid);
            }
            db.update("update project_tasks set last_status=?,last_progress=? where task_id=?",t.getStatus(),t.getProgress(),tid);
            if(!t.getStatus().equals(fresh.get("last_status")))access.activity(a,"TASK_STATUS_CHANGED",doc);else access.changed(pid);
        });
    }
}
