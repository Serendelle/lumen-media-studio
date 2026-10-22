# LUMEN V3 实施与验收记录

实施日期：2026-10-21 至 2026-10-22。开发者署名保持 Serendelle 个人独立开发；此次提交不添加共同作者。

## 1. 真实基线与增量边界

检查基线提交 `e6e38b1`，实际工程为 `backend/` Spring Boot 3.5.7 / Java 17、`frontend/` Vue 3 + TypeScript / React 首页与导航。JWT + BCrypt、SQLite / PostgreSQL Flyway V1–V4、媒体 TaskEngine、任务 SSE、Office 转 PDF、材料规范官、匿名收集、结构化协作表格、统计与 XLSX 导出均有实际代码和测试。

当前不存在 `flows` 项目表。此次复用 **spec_projects**，增加关联表与独立团队 API；材料规范官与团队工作台使用同一项目 ID。之前的 `spec_form_members` 只管理收集表格，不能当成项目成员。团队角色与公开收集的“仅看自己 / 全员编辑 / 指定编辑”继续分别校验，加入项目不会自动获取匿名收集的私有行、名单或个人文件库。

保留所有旧路由、媒体与智能功能、React 黑色点阵首页。团队入口使用现有 Vue 工作台品牌，未引入另一套后端或 Office 编辑器。

## 2. 各阶段实际代码

| 阶段 | 实际交付 | 验证方式 |
|---|---|---|
| Phase 1 基线 | 复用源码模型、认证、存储、TaskEngine 与现有收集权限 | 原代码审查及后续全量回归 |
| Phase 2 成员 | 项目创建/列表/介绍、邀请已有账号、改角色、移除、退出、负责人删除、版本冲突 | 双数据库真实 JWT / MockMvc 集成测试 |
| Phase 3 腾讯文档 | Provider 适配、严格官方域名校验、链接范围、真实导出回传、不可变版本 | URL 攻击测试及真实 XLSX 字节/哈希/权限验证 |
| Phase 4 实时 | 项目 SSE、会话在线状态、事务提交后通知、实际任务状态/进度、撤权关闭、重连快照 | 真实 SseEmitter 测试；前端 JWT fetch SSE |
| Phase 5 共享与检查 | 库存/类型筛选/版本/下载预览/归档、人工审核、待办、服务端事实检查、授权 AI 建议、校验 ZIP | 实际文件、数据库、输出解析与脚本模型测试 |
| Phase 6 部署 | Dockerfile、Compose、Nginx、HTTPS、PG 与文件卷、配置和备份恢复/发布脚本 | 当前仅静态及脚本安全测试；本机无 Docker |
| Phase 7 验收 | 自动化用例、现有功能回归、本地重启与升级记录 | 结果见第 8 节；外部账号/两设备/云主机另行验收 |

## 3. 修改文件与数据库

后端新增 `backend/src/main/java/com/houqing/tools/video_compressor_backend/collaboration/`：

- `ProjectAccessService`：成员、角色、行锁、文档可见范围、事务后通知。
- `ProjectService`：项目、成员、待办、发布状态及分页审计。
- `DocumentProvider`、`TencentDocsLinkProvider`、`DocumentIntegrationService`：合法链接与导出回传适配。
- `ProjectFileService`：共享文件、不可变版本、真实上传人、保留期、授权下载、审核及归档。
- `ProjectEventHub`：沿用 Spring SSE 机制，按当前权限检查每次事件，JWT 到期关闭。
- `ProjectTaskService`：接入既有处理引擎，短事务复核授权及幂等、关联真实状态与输出。
- `ProjectDeliveryHandler`：审核快照打包、SHA-256 和 ZIP 重读校验、取消/超时/磁盘预算。
- `ProjectChecksService`：真实文件检查、受限结构解析、模型数据范围与最终权限复核。
- `CollaborationController`：团队 API、不缓存、受控下载和写操作限流。

已有 `StorageService` 加入共享引用保护，DB 删除成功提交后才删磁盘；自动过期清理也保护项目版本。`SpecService` 创建时立即 flush 以兼容 JDBC 事务；删除项目后恢复普通文件保留期；修改原材料规范会回到准备状态并通知团队。`CancellationToken` 增加供 ZIP 循环使用的取消检查。

全量回归中还修复了两项实际环境问题：`ProcessRunner` / `ExifToolRunner` / `MetadataService` 为机器 JSON 分离 stdout 与 stderr，保留有界诊断，避免 Windows Perl 地区设置警告污染元数据；`RemoveBackgroundHandler` 为模型显式设置应用缓存目录，避免继承不可写的 XDG_DATA_HOME。可用 `REMBG_MODEL_DIR` 配置共享缓存，默认留在应用 data/models/rembg；免费 u2net 模型只下载到忽略的本地数据目录，不进入源码或前端。

完整修改文件清单见 [v3-files.txt](v3-files.txt)。测试新增 `ProjectCollaborationTests`、`PostgresProjectCollaborationTests`、`ProjectAssistantTests`、`ProjectMediaTests`、`ProjectTaskSecurityTests`、`TencentDocsLinkProviderTests`、`ProjectUpgradeTests`、`ProcessRunnerOutputTests`，并保持原测试断言验证回归。

SQLite 与 PostgreSQL 同步新增 `V5__project_collaboration.sql`。`spec_projects` 增加 `description`、`team_version`、`publication_status`；旧 spec 的 version 保留原义。新增：

| 表 | 用途 |
|---|---|
| project_members | EDITOR / REVIEWER / VIEWER 成员及加入时间；OWNER 始终取原 owner_id |
| project_documents / project_document_members | 外链、标题、类型、维护版本及指定可见成员 |
| project_assets / project_file_versions | 同一资料的不可变文件版本、上传人、哈希及审核状态 |
| project_document_versions | 手动回传版本、导入人/时间与源文档 ID |
| project_bundle_sources | 交付包与每个来源版本；包权限须满足全部来源范围 |
| project_activities | 真实操作审计；受限文档活动按授权过滤 |
| project_todos | 待办及乐观版本 |
| project_tasks | 原 TaskEngine 任务、来源/结果版本、真实状态、进度及幂等键 |

使用外键与项目级级联清理，不删旧表、不重建旧项目。文件 owner_id 始终是实际项目负责人用户 ID，上传人另记 uploaded_by；成员不能凭项目权限访问负责人其他个人文件。项目版本保留至项目删除；归档不退还容量，删除项目后文件恢复普通个人库保留期。

## 4. API 清单

统一前缀 `/api/v2/projects`，JWT Bearer 鉴权。陌生人猜项目/文件 ID 返回 404，已有只读成员尝试写入返回 403。读取结果遵守当前文档可见范围；写入在项目事务锁内再次鉴权。

| 方法与相对路径 | 功能 |
|---|---|
| GET /，POST / | 分页列出本人创建/加入的项目；创建（复用 spec_projects） |
| GET /{id}，PATCH /{id}，DELETE /{id} | 查看、介绍与名称版本编辑、负责人删除 |
| POST /{id}/leave | 非负责人退出 |
| GET/POST /{id}/members | 成员列表/邀请已有用户 |
| PATCH/DELETE /{id}/members/{userId} | 负责人改角色/撤销成员 |
| GET/POST /{id}/documents | 可见文档分页、关联 |
| PATCH/DELETE /{id}/documents/{docId} | 带 version 维护、解除关联 |
| POST /{id}/documents/{docId}/imports | 上传实际导出文件，生成新版本 |
| GET /{id}/documents/{docId}/versions | 文档回传历史 |
| GET/POST /{id}/files | 文件分页/类型/归档筛选；上传，可传 assetId 新增版本 |
| GET /{id}/files/{assetId}/versions | 文件版本历史 |
| PATCH /{id}/files/{assetId} | 归档/取消归档 |
| GET /{id}/files/{versionId}/content | 实时鉴权下载；inline 仅允许安全媒体/PDF 类型 |
| POST /{id}/files/{versionId}/review | OWNER / REVIEWER 审批或要求修改及说明 |
| POST /{id}/files/{versionId}/tasks | OWNER / EDITOR 提交白名单媒体/文档处理任务 |
| GET/POST /{id}/todos，PATCH/DELETE /{id}/todos/{todoId} | 真实待办，修改需 version |
| GET /{id}/activities | 受当前可见范围约束的分页活动 |
| GET /{id}/events | JWT SSE；snapshot / change / presence，仅失效通知和在线 UID |
| GET /{id}/tasks | 最近任务的真实状态、进度和可见输出版本 |
| GET /{id}/checks | 服务端事实报告；不访问腾讯正文 |
| POST /{id}/assistant | 必须明确 consent:true 的模型建议 |
| POST /{id}/deliveries | 负责人从已审核最新文件生成校验 ZIP，需 idempotencyKey |
| POST /{id}/publication | 负责人带 version 切换 DRAFT / REVIEW / PUBLISHED |

PROJECT 角色：OWNER 全部管理；EDITOR 项目信息/待办/可见文件与文档写入；REVIEWER 审核及读取；VIEWER 读取。文档范围只允许 OWNER 改变。负责人不能直接退出或降级；本版未实现所有权转移。发布必须有最新文件、全部审核通过且待办完成；新版本、规范或内容维护会回到 DRAFT。

边界：分页最大 100，成员 200，文档 200，待办 500；共享文件单个 1GiB（同时受全局上传限额约束），文档回传 200MiB；项目累计 2000 版本 / 20GiB。写操作每账号 120 次/分钟，项目 AI 每账号 1 次/分钟。最多 8 个实时连接/用户，服务进程总连接上限 2048。

## 5. 页面与使用步骤

新增 `frontend/src/views/TeamWorkspaceView.vue`、`frontend/src/api/collaboration.ts` 和 `components/collaboration/` 下的成员、文档、文件、待办任务、检查 AI 面板及统一样式。路由新增 `/workspace/team`、`/workspace/team/:id`，React 工作台侧栏增加“团队协作”。

1. 两个用户先各自注册 LUMEN。负责人打开团队协作，复用旧项目或新建项目，按已注册用户名邀请。
2. 负责人设置文档的所有成员/指定成员可见范围；在腾讯端独立设置编辑分享权限。
3. 打开腾讯官方新标签进行真正协作，导出受支持的文件，回到相应文档导入新版本。
4. 文件列表查看版本、实际哈希和上传人；视频可提交 MP3 音轨提取，输出单独保存。其他白名单操作可通过已有媒体/文档工作台或项目 API 使用。
5. 查看事实报告，按需明确授权 AI 对元数据和指标给出整理建议。审核员审批真实版本；负责人完成待办后发布或生成 ZIP。

界面含加载/空列表/失败/权限错误、进度、版本历史、409 草稿保留、SSE 重连、响应式布局。外链用 noopener noreferrer，不使用 iframe 或未经授权的 SDK。更详细的腾讯能力和当前官方依据见 [腾讯文档说明](tencent-documents.md)。

## 6. 事实检查、隐私与交付

文件字节、大小与 SHA-256 从本地存储读取；格式由现有真实类型检测判断；视频/音频使用现有 FFprobe 与 SpecChecker；已确认命名模板核对布局/非空字段与数字序号，不使用私有名单做身份核验。PDF 真实打开读取页数；DOCX/XLSX 做结构解析并展示有界的占位符/首个非空行字段候选。首行可能是数据，界面字段候选不等于已经确认的业务列定义。

缺失检查是**当前可见文件对应的材料类型**，不等于按私有名单逐人验收；原材料规范官仍承担逐人匹配与模板填充。详细文件解析单次限最新 100 份，汇总与缺失类型统计使用全部可见文件；报告明确标示截断。

AI 复用原 LlmClient，不引入新模型引擎。它只收到明确授权的当前可见文件名称、类型、实测事实和检查结果，不发送表格单元格/字段值、文件正文、私有名单或腾讯正文。文件名仍可能含个人信息，因此 UI 单独要求数据发送同意并提示服务商费用。模型返回前再次确认成员和可见文件未变化；结果仅作建议，不自动改事实或审批。

交付包保存提交时的已审核最新版本快照，逐文件校验哈希、写入 manifest.json，再重新读取 ZIP 验证每个条目的大小与哈希。输出归档在单独资源中，不自动纳入下一轮交付材料；手动重新纳入包时继续递归检查所有祖先文件权限，并做环检测、深度限制和访问缓存。任何成员读取包须同时具备全部来源文件权限；收回一个受限文档即可撤回对应包及嵌套包的项目下载。已下载到对方设备的副本无法追溯删除，开始传输的响应也无法撤回已经发出的字节。

任务幂等键必须对应相同类型、参数与输入，冲突返回 409。实际处理成功但项目版本额度已满时，项目任务保存 OUTPUT_UNAVAILABLE 与明确说明，处理引擎状态单独保留为 SUCCEEDED，不虚报共享结果可用，也不每秒永久重试登记。

SSE 只推送需要重新获取状态的通知，私密名称/正文/链接不会发给其他成员。每次 dispatch / heartbeat 验证成员与 JWT；真正离开成员由显式接口操作，断网只改变连接在线状态。任务状态来自 TaskEngine 数据库，服务端每秒检查已关联任务并通过同一 SSE 传输更新；单实例部署是当前实现边界。

## 7. 本地与腾讯云部署

本地沿用 README 的 Windows 启动脚本与已有 `.env`，运行数据库自动从 V4 升 V5。更新前应停服务并用 SQLite backup API 做一致快照，同时保留 storage 原件；旧版本 JAR 不能直接使用已升级结构回滚，需连同一致数据库/文件备份恢复。

```powershell
cd backend
.\mvnw.cmd -B -ntp -DskipTests package
cd ..\frontend
npm ci
npm run build
cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-windows.ps1 -NoBrowser
```

默认地址 `http://localhost:8080/workspace/team`。多人需访问同一局域网或 HTTPS 服务器地址；localhost 只能访问当前设备。

云主机目标为 Ubuntu 24.04、4vCPU / 8GiB / 100GiB。完整配置、命令、PG 与文件一致备份、恢复、日志、HTTPS、更新回滚见 [V3 云部署指南](deployment-v3.md)。镜像/前端不嵌入密钥，DB 无宿主公开端口，API 并发默认 1、FFmpeg 2 线程、任务 30 分钟、API 5GiB 上限。配置项复用 APP_*、LUMEN_DB_*、DEEPSEEK_*；部署专用值在 deploy/.env.example。资源购买和云主机创建未执行。

COS 直传当前没有配置或实现；本地存储一直可用。未来适配应维持文件 owner_id 与项目版本关联，服务端按 project/member 发放指定对象键、短时效、单文件大小/类型/校验约束的凭证，完成后 HEAD 校验与异步实际解析，通过后才登记文件；桶权限、SDK 和费用需另行确认，不可由客户端回报“上传成功”直接信任。当前没有声称已接入 COS。

## 8. 真实测试记录

首轮：`v3-project-tests.log`，18 项通过、无失败/错误/跳过，覆盖双数据库团队权限、XLSX 版本、SSE、ZIP、链接攻击及旧 SQLite 升级。后续全量结果更新在本节，不能把仅编译成功当作场景通过。

前端 `npm run build` 已通过，`npm run test:collaboration` 3 项链接安全测试通过；仍有原 Monaco 大分包提示。部署 Python 8 项安全测试与 Bash/YAML/XML 静态检查通过；没有 Docker，因此未执行实际镜像构建、容器启动、重启持久化或真实 PG 恢复演练。

### 最终运行结果

| 检查 | 实际结果 | 本机日志 |
|---|---|---|
| 完整后端回归 | 183 项，0 失败 / 0 错误；182 通过，1 项真实付费 AiLiveTests 按约定跳过 | data/run/v3-full-tests-final.log |
| 最终任务安全 + 双数据库协作复核 | 20 项通过，0 失败 / 错误 / 跳过；含新增幂等冲突、嵌套 ZIP 和2000版本配额用例 | data/run/v3-security-final.log |
| 原媒体处理 | 完整回归中的17项全部通过，含视频压缩、提取音轨、元数据读写清理、PDF/图片、实际 rembg 推理 | 同完整后端日志 |
| 实际团队媒体处理 | MP4→MP3、XLSX→PDF、损坏PDF拒绝3项通过；原字节/哈希及派生结果权限均核对 | 同完整后端日志 |
| SQLite / PostgreSQL 升级 | V4真实收集记录升级V5后保留；再次迁移为0变更，旧Pre-Flyway升级通过 | ProjectUpgradeTests / SqliteUpgradeTest |
| AI 授权与隐私 | 5项脚本模型测试通过；隐藏资料过滤、表头/单元格不出境、请求中撤员/撤scope拒绝旧结果 | ProjectAssistantTests |
| Vue/TypeScript/Vite | 生产构建通过，仅原 Monaco 大分包提示 | data/run/v3-frontend-startup.log |
| 前端小范围测试 | 表单冲突合并4项、腾讯链接安全3项通过 | npm run test:forms / test:collaboration |
| 部署脚本安全 | Python 8项通过，Shell/YAML/XML静态检查通过 | data/run/v3-deploy-tests-final.log |
| 发布内容检查 | 386个工作文件检查通过，无密钥命中或超大源码文件；.env与运行时数据均忽略 | data/run/check-publish-content.py |
| 本地启动 | 新JAR实际启动，8080端口健康UP；9项工具均available | data/logs/backend.out.log |

2026-10-22 本机已使用 SQLite backup API 创建 `data/run/backups/before-v3-20261022-125616.db`，备份 integrity_check=ok。启动升级后数据库为 V5、integrity_check=ok，原3个账号、1个材料项目、2个文件和7个任务计数保持不变；收集行/材料项原均为0。新功能测试使用隔离目录及独立 PostgreSQL 进程，没有在实际库植入演示成员或虚构资料。

运行地址：`http://localhost:8080`，团队入口 `http://localhost:8080/workspace/team`。本地构建运行JDK24，Maven release=17；Dockerfile使用Java17镜像，但容器未实际执行。测试中有预期的并发唯一约束日志及测试PG退出时的连接警告；这些不计为用例通过的替代证据，断言与真实文件结果均独立验证。

## 9. 尚未完成的外部验收与后续迭代

- **场景 G 尚未验收**：用户已选择此次先交付本地版本与部署文件。当前机器没有 Docker/Podman；部署文件可审查，但需在有 Docker 的目标机真实启动并做持久化/恢复演练。
- 腾讯网页内的真实双账号编辑、两台设备与公网 HTTPS 联调尚未执行；自动化仅验证 LUMEN 的独立账号、DB、文件、SSE 和权限。当前没有腾讯账号、实际文档或云主机可供外部验收，也未绕过此前的浏览器访问拒绝。
- 没有调用真实付费模型，AI 验证使用脚本模型，覆盖授权、数据过滤和请求过程中撤权；上线前需使用者确认模型服务与费用后做真实质量评估。
- 当前单节点 SSE 与本地盘适用于初始规模；多实例前需共享事件总线与任务调度/存储协调。COS 直传、腾讯正式授权 API、所有权转移、跨项目资料迁移和更细粒度材料项映射属于后续迭代。
- 新团队库不会自动公开旧匿名提交、名单和模板；如需共享旧收集资料，应新增负责人显式选择与审计的发布流程，保留“每人仅看自己”的承诺。

本记录区分本地实际交付、自动化通过和外部待验收事项；不能据此宣称腾讯内容双向同步、云端已部署或两设备已完成整体验收。
