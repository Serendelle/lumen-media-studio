package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.auth.UserRepository;
import com.houqing.tools.video_compressor_backend.common.*;
import com.houqing.tools.video_compressor_backend.spec.SpecService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.houqing.tools.video_compressor_backend.collaboration.ProjectAccessService.*;

@Service
public class ProjectService {
    private final JdbcTemplate db; private final ProjectAccessService access; private final SpecService specs; private final UserRepository users;
    private final ProjectFileService files;
    public ProjectService(JdbcTemplate db,ProjectAccessService access,SpecService specs,UserRepository users,ProjectFileService files) {this.db=db;this.access=access;this.specs=specs;this.users=users;this.files=files;}
    public Map<String,Object> list(long uid,int page,int size) {
        page=Math.max(0,page); size=size(size);
        var ids=db.queryForList("select p.id from spec_projects p where p.owner_id=? or exists(select 1 from project_members m where m.project_id=p.id and m.user_id=?) order by p.updated_at desc,p.id limit ? offset ?",String.class,uid,uid,size,(long)page*size);
        long total=db.queryForObject("select count(*) from spec_projects p where p.owner_id=? or exists(select 1 from project_members m where m.project_id=p.id and m.user_id=?)",Long.class,uid,uid);
        return Map.of("items",ids.stream().map(id->get(uid,id)).toList(),"page",page,"total",total);
    }
    public Map<String,Object> get(long uid,String pid) {
        Access a=access.require(uid,pid);
        Map<String,Object> p=db.queryForMap("select id,name,description,publication_status,team_version,owner_id,created_at,updated_at from spec_projects where id=?",pid);
        Map<String,Object> out=new LinkedHashMap<>(); out.put("id",pid);out.put("name",p.get("name"));out.put("description",p.get("description"));out.put("status",p.get("publication_status"));out.put("version",p.get("team_version"));out.put("ownerId",p.get("owner_id"));out.put("role",a.role());out.put("createdAt",p.get("created_at"));out.put("updatedAt",p.get("updated_at"));return out;
    }
    @Transactional public Map<String,Object> create(long uid,JsonNode body) {
        String name=text(body.path("name").asText(),2,80,"项目名称"); String description=text(body.path("description").asText(),0,2000,"介绍");
        var p=specs.create(uid,name); db.update("update spec_projects set description=? where id=?",description,p.getId());
        access.activity(access.require(uid,p.getId()),"PROJECT_CREATED",null);return get(uid,p.getId());
    }
    @Transactional public Map<String,Object> update(long uid,String pid,JsonNode body) {
        Access a=access.lock(uid,pid); requireVersion(pid,body);
        String name=text(body.path("name").asText(),2,80,"项目名称"); String description=text(body.path("description").asText(),0,2000,"介绍");
        db.update("update spec_projects set name=?,description=?,team_version=team_version+1,publication_status='DRAFT' where id=?",name,description,pid);
        access.activity(a,"PROJECT_UPDATED",null);return get(uid,pid);
    }
    @Transactional public void delete(long uid,String pid) {
        access.lock(uid,pid); access.owner(uid,pid); specs.delete(uid,pid); access.changed(pid);
    }
    public List<Map<String,Object>> members(long uid,String pid) {
        access.require(uid,pid);
        return db.query("select u.id,u.username,u.display_name,'OWNER' as role,p.created_at as joined_at from spec_projects p join users u on p.owner_id=u.id where p.id=? union all select u.id,u.username,u.display_name,m.role,m.joined_at from project_members m join users u on u.id=m.user_id where m.project_id=? order by joined_at",(r,n)->Map.<String,Object>of("userId",r.getLong("id"),"username",r.getString("username"),"displayName",r.getString("display_name"),"role",r.getString("role"),"joinedAt",r.getLong("joined_at")),pid,pid);
    }
    @Transactional public List<Map<String,Object>> invite(long uid,String pid,JsonNode body) {
        Access a=access.lock(uid,pid); access.owner(uid,pid); String role=memberRole(body.path("role").asText());
        var u=users.findByUsernameIgnoreCase(text(body.path("username").asText(),1,80,"用户名")).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND,"该用户尚未注册"));
        if(u.getId()==a.ownerId()) invalid("项目负责人已在成员列表中");
        if(memberIds(pid).size()>=200) invalid("单项目最多200名成员");
        if(memberIds(pid).contains(u.getId())) invalid("该用户已是成员，请修改角色");
        db.update("insert into project_members(project_id,user_id,role,joined_at) values(?,?,?,?)",pid,u.getId(),role,System.currentTimeMillis());
        access.activity(a,"MEMBER_JOINED",null);return members(uid,pid);
    }
    private List<Long> memberIds(String pid){return access.memberIds(pid);}
    @Transactional public void role(long uid,String pid,long target,JsonNode body) {
        Access a=access.lock(uid,pid); access.owner(uid,pid); String role=memberRole(body.path("role").asText());
        if(target==a.ownerId()) invalid("不能变更负责人角色");
        if(db.update("update project_members set role=? where project_id=? and user_id=?",role,pid,target)!=1) throw missing();
        access.activity(a,"MEMBER_ROLE_CHANGED",null);
    }
    @Transactional public void remove(long uid,String pid,long target,boolean leave) {
        Access a=access.lockMember(uid,pid);
        if(leave && target!=uid) throw denied(); if(!leave) access.owner(uid,pid);
        if(target==a.ownerId()) invalid("负责人不能退出，请先完成项目或删除项目");
        if(db.update("delete from project_members where project_id=? and user_id=?",pid,target)!=1) throw missing();
        // A removed member must not regain a selected link if invited again later.
        db.update("delete from project_document_members where user_id=? and document_id in(select id from project_documents where project_id=?)",target,pid);
        db.update("update project_todos set assignee_id=null,version=version+1 where project_id=? and assignee_id=?",pid,target);
        access.activity(a,leave?"MEMBER_LEFT":"MEMBER_REMOVED",null);
    }
    public Map<String,Object> activities(long uid,String pid,int page,int size) {
        Access actor=access.require(uid,pid);page=Math.max(0,page);size=size(size);
        // Filter restricted documents before pagination; ordinary activities contain no file names or contents.
        var all=db.queryForList("select id,action,actor_name,document_id,created_at from project_activities where project_id=? order by created_at desc,id desc",pid).stream()
                .filter(r->r.get("document_id")==null || actor.role().equals("OWNER") || access.documentCanRead(uid,pid,(String)r.get("document_id"))).toList();
        int from=(int)Math.min(all.size(),(long)page*size),to=Math.min(all.size(),from+size);
        return Map.of("items",all.subList(from,to).stream().map(r->Map.of("id",r.get("id"),"action",r.get("action"),"actorName",r.get("actor_name"),"createdAt",r.get("created_at"))).toList(),"total",all.size(),"page",page);
    }
    public List<Map<String,Object>> todos(long uid,String pid) {
        access.require(uid,pid);return db.query("select * from project_todos where project_id=? order by created_at,id",(r,n)->{
            Map<String,Object> m=new LinkedHashMap<>();m.put("id",r.getString("id"));m.put("title",r.getString("title"));m.put("status",r.getString("status"));m.put("assigneeId",r.getObject("assignee_id"));m.put("version",r.getInt("version"));m.put("createdAt",r.getLong("created_at"));m.put("updatedAt",r.getLong("updated_at"));return m;},pid);
    }
    @Transactional public List<Map<String,Object>> todo(long uid,String pid,String id,JsonNode body,boolean delete) {
        Access a=access.lock(uid,pid);
        if(id!=null) {
            var rows=db.queryForList("select version from project_todos where id=? and project_id=?",id,pid);if(rows.isEmpty()) throw missing();
            if(!delete && ((Number)rows.get(0).get("version")).intValue()!=version(body)) throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        if(delete) db.update("delete from project_todos where id=? and project_id=?",id,pid);
        else {
            String title=text(body.path("title").asText(),1,200,"待办标题");String status=body.path("status").asText("OPEN"); if(!Set.of("OPEN","DOING","DONE").contains(status)) invalid("待办状态无效");
            JsonNode assigneeNode=body.path("assigneeId");if(!assigneeNode.isMissingNode()&&!assigneeNode.isNull()&&(!assigneeNode.isIntegralNumber()||!assigneeNode.canConvertToLong()))invalid("待办负责人必须是用户ID或空值");
            Long assignee=assigneeNode.isIntegralNumber()?assigneeNode.asLong():null;
            if(assignee!=null&&!memberIds(pid).contains(assignee)) invalid("负责人选项必须是现有成员"); long now=System.currentTimeMillis();
            if(id==null) {if(todos(uid,pid).size()>=500) invalid("待办最多500项");db.update("insert into project_todos(id,project_id,title,status,assignee_id,created_at,updated_at) values(?,?,?,?,?,?,?)",UUID.randomUUID().toString(),pid,title,status,assignee,now,now);}
            else db.update("update project_todos set title=?,status=?,assignee_id=?,version=version+1,updated_at=? where id=? and project_id=?",title,status,assignee,now,id,pid);
        }
        invalidatePublication(pid);access.activity(a,"TODO_CHANGED",null);return todos(uid,pid);
    }
    @Transactional public Map<String,Object> publication(long uid,String pid,JsonNode body) {
        Access a=access.lock(uid,pid); access.owner(uid,pid);requireVersion(pid,body);
        String status=body.path("status").asText();if(!Set.of("DRAFT","REVIEW","PUBLISHED").contains(status)) invalid("发布状态无效");
        if(status.equals("PUBLISHED")) {
            var latest=files.allLatest(uid,pid);
            int todos=db.queryForObject("select count(*) from project_todos where project_id=? and status<>'DONE'",Integer.class,pid);
            if(latest.isEmpty()||latest.stream().anyMatch(v->!"APPROVED".equals(v.get("reviewStatus")))||todos>0) invalid("发布前需至少一份可访问文件、所有最新文件审核通过且待办已完成");
        }
        db.update("update spec_projects set publication_status=?,team_version=team_version+1 where id=?",status,pid);access.activity(a,"PUBLICATION_CHANGED",null);return get(uid,pid);
    }
    void requireVersion(String pid,JsonNode body) { if(db.queryForObject("select team_version from spec_projects where id=?",Integer.class,pid)!=version(body)) throw new ApiException(ErrorCode.VERSION_CONFLICT); }
    public void invalidatePublication(String pid) {db.update("update spec_projects set publication_status='DRAFT',team_version=team_version+1 where id=?",pid);}
    static String memberRole(String role) {if(!Set.of("EDITOR","REVIEWER","VIEWER").contains(role)) invalid("成员角色无效");return role;}
}
