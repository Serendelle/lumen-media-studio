package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.common.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v2/projects")
public class CollaborationController {
    private final ProjectService projects;private final DocumentIntegrationService documents;private final ProjectFileService files;
    private final ProjectEventHub events;private final ProjectChecksService checks;private final ProjectTaskService tasks;
    private final Map<Long,long[]> mutationWindows=new java.util.concurrent.ConcurrentHashMap<>();
    public CollaborationController(ProjectService projects,DocumentIntegrationService documents,ProjectFileService files,ProjectEventHub events,ProjectChecksService checks,ProjectTaskService tasks){this.projects=projects;this.documents=documents;this.files=files;this.events=events;this.checks=checks;this.tasks=tasks;}
    @ModelAttribute public void noCache(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");if(request.getMethod().equals("GET"))return;
        long uid=CurrentUser.id(),now=System.currentTimeMillis();
        synchronized(mutationWindows){mutationWindows.entrySet().removeIf(e->e.getValue()[0]<now-60000);
            if(mutationWindows.size()>=10000&&!mutationWindows.containsKey(uid))throw new ApiException(ErrorCode.RATE_LIMITED);
            long[] window=mutationWindows.computeIfAbsent(uid,k->new long[]{now,0});if(++window[1]>120)throw new ApiException(ErrorCode.RATE_LIMITED,"每账号每分钟最多120次项目写操作");}
    }
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return projects.list(CurrentUser.id(),page,size);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(@RequestBody JsonNode body){return projects.create(CurrentUser.id(),body);}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable String id){return projects.get(CurrentUser.id(),id);}
    @PatchMapping("/{id}") public Map<String,Object> update(@PathVariable String id,@RequestBody JsonNode body){return projects.update(CurrentUser.id(),id,body);}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id){projects.delete(CurrentUser.id(),id);}
    @PostMapping("/{id}/leave") @ResponseStatus(HttpStatus.NO_CONTENT) public void leave(@PathVariable String id){long uid=CurrentUser.id();projects.remove(uid,id,uid,true);}
    @GetMapping("/{id}/members") public List<Map<String,Object>> members(@PathVariable String id){long uid=CurrentUser.id();Set<Long> online=events.online(id);return projects.members(uid,id).stream().map(row->{var m=new LinkedHashMap<>(row);m.put("online",online.contains(((Number)m.get("userId")).longValue()));return (Map<String,Object>)m;}).toList();}
    @PostMapping("/{id}/members") public List<Map<String,Object>> invite(@PathVariable String id,@RequestBody JsonNode body){return projects.invite(CurrentUser.id(),id,body);}
    @PatchMapping("/{id}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void role(@PathVariable String id,@PathVariable long userId,@RequestBody JsonNode body){projects.role(CurrentUser.id(),id,userId,body);}
    @DeleteMapping("/{id}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void remove(@PathVariable String id,@PathVariable long userId){projects.remove(CurrentUser.id(),id,userId,false);}
    @GetMapping("/{id}/documents") public Map<String,Object> documents(@PathVariable String id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return documents.list(CurrentUser.id(),id,page,size);}
    @PostMapping("/{id}/documents") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> document(@PathVariable String id,@RequestBody JsonNode body){return documents.create(CurrentUser.id(),id,body);}
    @PatchMapping("/{id}/documents/{docId}") public Map<String,Object> updateDocument(@PathVariable String id,@PathVariable String docId,@RequestBody JsonNode body){return documents.update(CurrentUser.id(),id,docId,body);}
    @DeleteMapping("/{id}/documents/{docId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void unlink(@PathVariable String id,@PathVariable String docId){documents.unlink(CurrentUser.id(),id,docId);}
    @PostMapping("/{id}/documents/{docId}/imports") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> imports(@PathVariable String id,@PathVariable String docId,@RequestParam MultipartFile file){return documents.imports(CurrentUser.id(),id,docId,file);}
    @GetMapping("/{id}/documents/{docId}/versions") public List<Map<String,Object>> documentVersions(@PathVariable String id,@PathVariable String docId){return documents.versions(CurrentUser.id(),id,docId);}
    @GetMapping("/{id}/files") public Map<String,Object> files(@PathVariable String id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size,@RequestParam(required=false)String kind,@RequestParam(defaultValue="false")boolean archived){return files.list(CurrentUser.id(),id,page,size,kind,archived);}
    @PostMapping("/{id}/files") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> upload(@PathVariable String id,@RequestParam MultipartFile file,@RequestParam(required=false)String assetId){return files.upload(CurrentUser.id(),id,file,assetId,null);}
    @GetMapping("/{id}/files/{assetId}/versions") public List<Map<String,Object>> versions(@PathVariable String id,@PathVariable String assetId){return files.versions(CurrentUser.id(),id,assetId);}
    @PatchMapping("/{id}/files/{assetId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void archive(@PathVariable String id,@PathVariable String assetId,@RequestBody JsonNode body){if(!body.path("archived").isBoolean())ProjectAccessService.invalid("archived必须是布尔值");files.archive(CurrentUser.id(),id,assetId,body.path("archived").asBoolean());}
    @GetMapping("/{id}/files/{versionId}/content") public ResponseEntity<Resource> content(@PathVariable String id,@PathVariable String versionId,@RequestParam(defaultValue="false")boolean inline){
        var f=files.file(CurrentUser.id(),id,versionId);boolean safeInline=inline&&Set.of("VIDEO","AUDIO","PDF","IMAGE").contains(f.getMediaType())&&!"image/svg+xml".equals(f.getMimeType());
        ContentDisposition disposition=(safeInline?ContentDisposition.inline():ContentDisposition.attachment()).filename(f.getOriginalName(),StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'").contentType(MediaType.parseMediaType(f.getMimeType())).contentLength(f.getSizeBytes()).body(new FileSystemResource(files.path(f)));
    }
    @PostMapping("/{id}/files/{versionId}/review") public Map<String,Object> review(@PathVariable String id,@PathVariable String versionId,@RequestBody JsonNode body){return files.review(CurrentUser.id(),id,versionId,body);}
    @GetMapping("/{id}/todos") public List<Map<String,Object>> todos(@PathVariable String id){return projects.todos(CurrentUser.id(),id);}
    @PostMapping("/{id}/todos") public List<Map<String,Object>> todo(@PathVariable String id,@RequestBody JsonNode body){return projects.todo(CurrentUser.id(),id,null,body,false);}
    @PatchMapping("/{id}/todos/{todoId}") public List<Map<String,Object>> updateTodo(@PathVariable String id,@PathVariable String todoId,@RequestBody JsonNode body){return projects.todo(CurrentUser.id(),id,todoId,body,false);}
    @DeleteMapping("/{id}/todos/{todoId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deleteTodo(@PathVariable String id,@PathVariable String todoId){projects.todo(CurrentUser.id(),id,todoId,null,true);}
    @GetMapping("/{id}/activities") public Map<String,Object> activities(@PathVariable String id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="30")int size){return projects.activities(CurrentUser.id(),id,page,size);}
    @GetMapping(value="/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter events(@PathVariable String id){
        long uid=CurrentUser.id();Object principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Instant expiry=principal instanceof Jwt jwt?jwt.getExpiresAt():null;if(expiry==null)throw new ApiException(ErrorCode.UNAUTHORIZED);return events.subscribe(uid,id,expiry.toEpochMilli());
    }
    @GetMapping("/{id}/checks") public JsonNode checks(@PathVariable String id){return checks.report(CurrentUser.id(),id);}
    @PostMapping("/{id}/assistant") public JsonNode assistant(@PathVariable String id,@RequestBody JsonNode body){return checks.assistant(CurrentUser.id(),id,body);}
    @GetMapping("/{id}/tasks") public List<Map<String,Object>> tasks(@PathVariable String id){return tasks.list(CurrentUser.id(),id);}
    @PostMapping("/{id}/files/{versionId}/tasks") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> process(@PathVariable String id,@PathVariable String versionId,@RequestBody JsonNode body){return tasks.submit(CurrentUser.id(),id,versionId,body);}
    @PostMapping("/{id}/deliveries") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Object> deliver(@PathVariable String id,@RequestBody JsonNode body){return tasks.deliver(CurrentUser.id(),id,body);}
    @PostMapping("/{id}/publication") public Map<String,Object> publish(@PathVariable String id,@RequestBody JsonNode body){return projects.publication(CurrentUser.id(),id,body);}
}
