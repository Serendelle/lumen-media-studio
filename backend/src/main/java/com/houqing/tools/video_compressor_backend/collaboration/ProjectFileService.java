package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.common.*;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static com.houqing.tools.video_compressor_backend.collaboration.ProjectAccessService.*;

/** Project grants wrap the existing personal storage; every immutable upload retains a real user owner. */
@Service
public class ProjectFileService {
    private final JdbcTemplate db;private final ProjectAccessService access;private final UploadService uploads;private final StorageService storage;private final TransactionTemplate tx;
    public ProjectFileService(JdbcTemplate db,ProjectAccessService access,UploadService uploads,StorageService storage,PlatformTransactionManager manager){this.db=db;this.access=access;this.uploads=uploads;this.storage=storage;this.tx=new TransactionTemplate(manager);}
    public Map<String,Object> upload(long uid,String pid,MultipartFile file,String assetId,String documentId) {
        Access a=access.write(uid,pid);
        if(documentId!=null) access.requireDocument(uid,pid,documentId);
        if(assetId!=null){var asset=asset(uid,pid,assetId);String actual=(String)asset.get("document_id");if(documentId!=null&&!Objects.equals(documentId,actual))throw missing();documentId=actual;}
        if(file==null||file.isEmpty())invalid("请选择上传文件");if(file.getSize()>1024L*1024*1024)throw new ApiException(ErrorCode.FILE_TOO_LARGE,"团队单文件最大1GiB");
        MediaFile f=uploads.upload(a.ownerId(),file,EnumSet.of(MediaKind.VIDEO,MediaKind.AUDIO,MediaKind.IMAGE,MediaKind.PDF,MediaKind.DOCUMENT));
        String doc=documentId;
        try {
            if(doc!=null)validateDocumentFormat(pid,doc,f);
            if(f.kind()==MediaKind.PDF) ProjectChecksService.pdfPages(storage.pathOf(f));
            // Imported Office data must be genuinely parseable, not an extension-only success.
            if(f.kind()==MediaKind.DOCUMENT) ProjectChecksService.documentFields(storage.pathOf(f),StorageService.extensionOf(f.getStoredName()));
            String checksum=sha256(storage.pathOf(f));
            return tx.execute(s->{Access locked=access.lock(uid,pid);if(doc!=null){access.requireDocument(uid,pid,doc);validateDocumentFormat(pid,doc,f);}
                if(assetId!=null)asset(uid,pid,assetId);
                return register(locked,f,assetId,doc,checksum,"FILE_UPLOADED");});
        }catch(RuntimeException e){storage.deleteFile(f);throw e;}
    }
    private void validateDocumentFormat(String pid,String doc,MediaFile file){String type=db.queryForObject("select document_type from project_documents where id=? and project_id=?",String.class,doc,pid);
        if(!new TencentDocsLinkProvider().importExtensions(type).contains(StorageService.extensionOf(file.getStoredName())))throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT,"实际文件格式与协作文档类型不匹配");}
    /** Caller holds a project transaction/lock. Used also for verified outputs of the existing engine. */
    Map<String,Object> register(Access a,MediaFile f,String assetId,String documentId,String checksum,String action) {
        String pid=a.projectId();int count=db.queryForObject("select count(*) from project_file_versions where project_id=?",Integer.class,pid);
        long bytes=db.queryForObject("select coalesce(sum(m.size_bytes),0) from project_file_versions v join media_files m on m.id=v.file_id where v.project_id=?",Long.class,pid);
        if(count>=2000||bytes+f.getSizeBytes()>20L*1024*1024*1024)invalid("项目已达到2000个版本或20GiB存储上限，请归档后由负责人规划清理");
        long now=System.currentTimeMillis();String aid=assetId;
        if(aid==null){aid=UUID.randomUUID().toString();db.update("insert into project_assets(id,project_id,document_id,created_at) values(?,?,?,?)",aid,pid,documentId,now);}
        else {var asset=asset(a.userId(),pid,aid);if(((Number)asset.get("archived")).intValue()!=0)invalid("请先取消归档再增加版本");}
        int number=db.queryForObject("select coalesce(max(version_number),0)+1 from project_file_versions where asset_id=?",Integer.class,aid);String id=UUID.randomUUID().toString();
        db.update("insert into project_file_versions(id,asset_id,project_id,document_id,file_id,version_number,uploaded_by,created_at,sha256) values(?,?,?,?,?,?,?,?,?)",id,aid,pid,documentId,f.getId(),number,a.userId(),now,checksum);
        if(documentId!=null){int dn=db.queryForObject("select coalesce(max(version_number),0)+1 from project_document_versions where document_id=?",Integer.class,documentId);
            db.update("insert into project_document_versions(id,document_id,file_version_id,version_number,imported_by,imported_at,sha256) values(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),documentId,id,dn,a.userId(),now,checksum);
            db.update("update project_documents set updated_at=? where id=?",now,documentId);
        }
        storage.keepUntil(f,Long.MAX_VALUE); // Explicit project retention until project deletion; regular library TTL cannot erase shared versions.
        if(action.equals("DELIVERY_READY"))db.update("update project_assets set archived=1 where id=?",aid);
        else db.update("update spec_projects set publication_status='DRAFT',team_version=team_version+1 where id=?",pid);
        access.activity(a,action,documentId);return version(a.userId(),pid,id);
    }
    public Map<String,Object> list(long uid,String pid,int page,int size,String kind,boolean archived){access.require(uid,pid);page=Math.max(0,page);size=size(size);
        var all=rows(pid).stream().filter(r->((Number)r.get("archived")).intValue()==(archived?1:0))
                .filter(r->kind==null||kind.isBlank()||kind.equals(r.get("media_type"))).filter(r->visible(uid,pid,r))
                .filter(r->((Number)r.get("version_number")).intValue()==db.queryForObject("select max(version_number) from project_file_versions where asset_id=?",Integer.class,r.get("asset_id"))).toList();
        int from=(int)Math.min(all.size(),(long)page*size);return Map.of("items",all.subList(from,Math.min(all.size(),from+size)).stream().map(this::view).toList(),"page",page,"total",all.size());}
    public List<Map<String,Object>> versions(long uid,String pid,String assetId){asset(uid,pid,assetId);return rows(pid).stream().filter(r->assetId.equals(r.get("asset_id"))).map(this::view).toList();}
    public Map<String,Object> version(long uid,String pid,String id){return view(raw(uid,pid,id));}
    Map<String,Object> raw(long uid,String pid,String id){access.require(uid,pid);return rows(pid).stream().filter(r->id.equals(r.get("id"))&&visible(uid,pid,r)).findFirst().orElseThrow(ProjectAccessService::missing);}
    Map<String,Object> asset(long uid,String pid,String aid){access.require(uid,pid);var rows=db.queryForList("select * from project_assets where id=? and project_id=?",aid,pid);if(rows.isEmpty()||!visible(uid,pid,rows.get(0)))throw missing();
        for(var r:db.queryForList("select id,document_id from project_file_versions where asset_id=?",aid))if(!visible(uid,pid,r))throw missing();return rows.get(0);}
    boolean visible(long uid,String pid,Map<String,Object> r){return visible(uid,pid,r,new HashSet<>(),new HashMap<>(),0);}
    private boolean visible(long uid,String pid,Map<String,Object> r,Set<String> path,Map<String,Boolean> memo,int depth){
        if(depth>64)return false; // Fail closed for malformed/cyclic history, never exhaust the request thread.
        String id=r.get("id").toString();if(memo.containsKey(id))return memo.get(id);
        if(r.get("document_id")!=null&&!access.documentCanRead(uid,pid,(String)r.get("document_id"))){memo.put(id,false);return false;}
        if(!path.add(id))return false;
        for(var source:db.queryForList("select v.id,v.project_id,v.document_id from project_bundle_sources b join project_file_versions v on v.id=b.source_version_id where b.output_version_id=?",id)){
            if(!pid.equals(source.get("project_id"))||!visible(uid,pid,source,path,memo,depth+1)){path.remove(id);memo.put(id,false);return false;}
        }
        path.remove(id);memo.put(id,true);return true;
    }
    public MediaFile file(long uid,String pid,String id){var a=access.require(uid,pid);var r=raw(uid,pid,id);return storage.require(a.ownerId(),(String)r.get("file_id"));}
    public Path path(MediaFile f){return storage.pathOf(f);}
    public List<Map<String,Object>> latest(long uid,String pid){return castItems(list(uid,pid,0,100,"",false));}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> castItems(Map<String,Object> out){return (List<Map<String,Object>>)out.get("items");}
    public List<Map<String,Object>> allLatest(long uid,String pid){access.require(uid,pid);return rows(pid).stream().filter(r->((Number)r.get("archived")).intValue()==0&&visible(uid,pid,r)).filter(r->((Number)r.get("version_number")).intValue()==db.queryForObject("select max(version_number) from project_file_versions where asset_id=?",Integer.class,r.get("asset_id"))).map(this::view).toList();}
    public void archive(long uid,String pid,String assetId,boolean archived){tx.executeWithoutResult(s->{var a=access.lock(uid,pid);var asset=asset(uid,pid,assetId);db.update("update project_assets set archived=? where id=?",archived?1:0,assetId);db.update("update spec_projects set publication_status='DRAFT',team_version=team_version+1 where id=?",pid);access.activity(a,archived?"FILE_ARCHIVED":"FILE_UNARCHIVED",(String)asset.get("document_id"));});}
    public Map<String,Object> review(long uid,String pid,String id,JsonNode body){return tx.execute(s->{var a=access.lockMember(uid,pid);if(!Set.of("OWNER","REVIEWER").contains(a.role()))throw denied();var r=raw(uid,pid,id);
        String status=body.path("status").asText();if(!Set.of("APPROVED","CHANGES_REQUESTED").contains(status))invalid("审核状态无效");String note=text(body.path("note").asText(),0,2000,"审核说明");
        db.update("update project_file_versions set review_status=?,review_note=?,reviewed_by=?,reviewed_at=? where id=?",status,note,uid,System.currentTimeMillis(),id);db.update("update spec_projects set publication_status='DRAFT',team_version=team_version+1 where id=?",pid);access.activity(a,"REVIEW_CHANGED",(String)r.get("document_id"));return version(uid,pid,id);});}
    private List<Map<String,Object>> rows(String pid){return db.queryForList("select v.*,a.archived,m.original_name,m.size_bytes,m.media_type,m.mime_type,u.display_name from project_file_versions v join project_assets a on a.id=v.asset_id join media_files m on m.id=v.file_id join users u on u.id=v.uploaded_by where v.project_id=? order by v.created_at desc,v.version_number desc,v.id",pid);}
    private Map<String,Object> view(Map<String,Object> r){Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.get("id"));m.put("assetId",r.get("asset_id"));m.put("versionNumber",r.get("version_number"));m.put("filename",r.get("original_name"));m.put("sizeBytes",r.get("size_bytes"));m.put("mediaType",r.get("media_type"));m.put("mimeType",r.get("mime_type"));m.put("sha256",r.get("sha256"));m.put("uploadedBy",r.get("uploaded_by"));m.put("uploaderName",r.get("display_name"));m.put("createdAt",r.get("created_at"));m.put("documentId",r.get("document_id"));m.put("reviewStatus",r.get("review_status"));m.put("reviewNote",r.get("review_note"));m.put("archived",((Number)r.get("archived")).intValue()!=0);return m;}
    public static String sha256(Path path){try(var in=Files.newInputStream(path)){MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)d.update(b,0,n);return HexFormat.of().formatHex(d.digest());}catch(Exception e){throw new ApiException(ErrorCode.INTERNAL_ERROR,"无法计算文件校验值");}}
}
