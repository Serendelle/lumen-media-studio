package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.*;
import com.houqing.tools.video_compressor_backend.common.*;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Immutable review snapshot, SHA verification before packaging and a real ZIP re-read after writing. */
@Component
public class ProjectDeliveryHandler implements MediaTaskHandler {
    private final JdbcTemplate db;private final StorageService storage;private final ObjectMapper mapper;
    private final com.houqing.tools.video_compressor_backend.config.AppProperties props;
    public ProjectDeliveryHandler(JdbcTemplate db,StorageService storage,ObjectMapper mapper,com.houqing.tools.video_compressor_backend.config.AppProperties props){this.db=db;this.storage=storage;this.mapper=mapper;this.props=props;}
    public String type(){return "project.deliver";}
    public void validate(JsonNode params,List<MediaFile> inputs){if(!params.path("projectId").isTextual()||!params.path("entries").isArray()||inputs.isEmpty()||inputs.size()>2000||inputs.size()!=params.path("entries").size())throw new ApiException(ErrorCode.VALIDATION_FAILED,"交付快照无效");}
    public HandlerResult execute(TaskContext ctx)throws Exception{
        validate(ctx.params(),ctx.inputs());String pid=ctx.params().path("projectId").asText();
        if(db.queryForObject("select count(*) from spec_projects where id=? and owner_id=?",Integer.class,pid,ctx.ownerId())!=1)throw ProjectAccessService.missing();
        Path zip=ctx.workDir().resolve("delivery.zip");var manifest=mapper.createArrayNode();int i=0;
        long deadline=System.nanoTime()+java.time.Duration.ofMinutes(Math.max(1,props.media().taskTimeoutMinutes())).toNanos();
        long required=ctx.inputs().stream().mapToLong(MediaFile::getSizeBytes).sum();
        if(Files.getFileStore(ctx.workDir()).getUsableSpace()<required+props.media().minFreeDiskMb()*1024L*1024)throw new ApiException(ErrorCode.INSUFFICIENT_DISK);
        try(var out=new ZipOutputStream(Files.newOutputStream(zip))){
            for(JsonNode entry:ctx.params().path("entries")){check(ctx,deadline);MediaFile f=ctx.inputs().get(i++);
                if(!f.getId().equals(entry.path("fileId").asText()))throw new ApiException(ErrorCode.OUTPUT_INVALID);
                int approved=db.queryForObject("select count(*) from project_file_versions where id=? and project_id=? and file_id=? and review_status='APPROVED'",Integer.class,entry.path("versionId").asText(),pid,f.getId());
                if(approved!=1||!ProjectFileService.sha256(ctx.inputPath(f)).equals(entry.path("sha256").asText()))throw new ApiException(ErrorCode.OUTPUT_INVALID,"审核状态或文件校验值已变化，请重新提交交付");
                String name=String.format(Locale.ROOT,"files/%04d_%s",i,StorageService.sanitizeName(f.getOriginalName()));out.putNextEntry(new ZipEntry(name));
                try(var in=Files.newInputStream(ctx.inputPath(f))){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){check(ctx,deadline);out.write(buffer,0,n);}}out.closeEntry();
                manifest.addObject().put("path",name).put("versionId",entry.path("versionId").asText()).put("sha256",entry.path("sha256").asText()).put("sizeBytes",f.getSizeBytes());ctx.progress(i*90/ctx.inputs().size(),"packaging");
            }
            out.putNextEntry(new ZipEntry("manifest.json"));out.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));out.closeEntry();
        }
        try(var verify=new ZipFile(zip.toFile())){if(verify.size()!=manifest.size()+1)throw new ApiException(ErrorCode.OUTPUT_INVALID);
            for(JsonNode entry:manifest){ctx.token().throwIfCancelled();try(var in=verify.getInputStream(verify.getEntry(entry.path("path").asText()))){var md=java.security.MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];long bytes=0;for(int n;(n=in.read(b))!=-1;){ctx.token().throwIfCancelled();md.update(b,0,n);bytes+=n;}if(!HexFormat.of().formatHex(md.digest()).equals(entry.path("sha256").asText())||bytes!=entry.path("sizeBytes").asLong())throw new ApiException(ErrorCode.OUTPUT_INVALID);}}
        }
        MediaFile output=storage.registerOutput(ctx.ownerId(),zip,"LUMEN_项目交付.zip",MediaKind.ARCHIVE,"application/zip",ctx.taskId());return new HandlerResult(output,mapper.createObjectNode().put("files",manifest.size()).put("verified",true).set("manifest",manifest));
    }
    private void check(TaskContext ctx,long deadline){ctx.token().throwIfCancelled();if(System.nanoTime()>deadline)throw new ApiException(ErrorCode.PROCESS_TIMEOUT);}
}
