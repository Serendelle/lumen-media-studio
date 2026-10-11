# 后端代码审计报告

- 审计对象：`video-compressor-backend`（原始交付版本，git 提交 `d5db2aa`）
- 技术栈（以真实代码为准）：Spring Boot 3.5.7 / Java 17 / Maven，无数据库、无鉴权，任务状态存于内存 `ConcurrentHashMap`
- 外部工具：FFmpeg/FFprobe、ImageMagick、Ghostscript（PDF）、Conda + rembg（抠图）
- 审计日期：2026-10-11
- 审计方法：逐文件阅读全部 25 个 Java 源文件与配置；在隔离目录中以真实 FFmpeg 9.0.2 / ImageMagick 7.1.2 启动原始 JAR 进行复现（脚本见附录 A）

> 「证据」一列中：**已复现** 表示对原始 JAR 发起真实请求得到的结果；**代码** 表示由源码路径直接推断，未单独构造攻击请求。

## 1. 模块关系（原始版本）

```
controller (5)                         service (6)                         外部进程
VideoController   ── /api/video/** ──▶ VideoService ──────────────────────▶ ffprobe / ffmpeg
AudioController   ── /api/audio/** ──▶ AudioService ──┐
ImageController   ── /api/image/** ──▶ ImageService ◀─┤ (executeProcess) ─▶ magick / conda run rembg
PdfController     ── /api/pdf/**   ──▶ PdfService   ──┤                  ─▶ magick (+Ghostscript)
MetadataController── /api/metadata/**▶ MetadataService┘                  ─▶ ffprobe / ffmpeg / magick
所有 *Controller ──▶ TaskService (内存 Map: PENDING/PROCESSING/COMPLETED/FAILED)
```

### 原始接口清单

| 方法 | 路径 | 同步/异步 | 说明 |
|---|---|---|---|
| POST | /api/video/upload | 同步 | 保存文件 + ffprobe 分析，返回 `videoInfo` 和 `uniqueFilename` |
| POST | /api/video/compress | 异步 | `mode=crf\|bitrate` |
| POST | /api/video/convert | 异步 | `targetFormat` 任意字符串 |
| POST | /api/video/watermark | 异步 | 图片水印 |
| GET | /api/video/progress/{taskId} | 同步 | 进度 |
| GET | /api/video/download/{filename} | 同步 | 下载输出目录中的任意文件名 |
| POST | /api/audio/extract | 异步 | mp3/wav/flac/aac |
| GET | /api/audio/progress/{taskId} | 同步 | |
| POST | /api/image/upload | 同步 | |
| POST | /api/image/process | 异步 | convert/resize/compress，打包为 zip |
| POST | /api/image/remove-background | 异步 | rembg |
| GET | /api/image/progress/{taskId} | 同步 | |
| POST | /api/pdf/process | 异步 | pdf-to-image / images-to-pdf |
| GET | /api/pdf/progress/{taskId} | 同步 | |
| POST | /api/metadata/upload | 同步 | |
| POST | /api/metadata/view | 同步 | |
| POST | /api/metadata/remove | 同步 | |
| POST | /api/metadata/modify | 同步 | 设备型号/拍摄时间/GPS |

## 2. 问题清单

### 2.1 高危

| ID | 文件位置 | 问题 | 严重程度 | 影响 | 修改建议 | 证据 | 状态 |
|---|---|---|---|---|---|---|---|
| H-01 | `VideoService.java:192`、`AudioService.java:46` | `targetFormat` 未经校验直接拼入输出路径；Windows 会按字面折叠 `..`，因此可把 FFmpeg 的输出写到任意目录 | 高 | 任意文件写入/覆盖（例如写入启动目录） | 使用格式白名单；输出路径由服务端生成，并校验必须位于存储根目录内 | **已复现**：`targetFormat="x\..\..\PWNED.mp4"` 在输出目录外生成了 `PWNED.mp4` | 已修复 |
| H-02 | `VideoService.java:112,185,250`、`AudioService.java:39`、`ImageService.java:80,147`、`PdfService.java:53,125`、`MetadataService.java:59,468,500` | 客户端传入的 `uniqueFilename`/`filenames` 直接 `resolve` 到上传目录，没有做包含校验 | 高 | 可读取或处理服务器上任意文件（元数据、转码后可下载） | 文件句柄改为服务端 ID，查库校验归属，路径做 `normalize + startsWith(root)` 校验 | **已复现**：`/api/metadata/view` 传 `..\secret.png` 返回了上传目录外文件的分辨率与大小 | 已修复 |
| H-03 | 全部 Controller | 没有任何鉴权；任务和文件全局共享；输出文件名可预测（`compressed_`+输入名、`processed_{taskId}.zip`） | 高 | 水平越权：任何人都能下载他人的处理结果、查询他人任务 | 增加注册/登录（JWT）；文件和任务带 `owner_id`，每次访问都校验归属；下载使用短期签名票据 | **已复现**：未登录直接 GET `/api/video/download/compressed_victim.mp4` 返回 200 | 已修复 |
| H-04 | `ImageService.java:187-211`、`VideoService.java:154-170`、`AudioService.java:51-87`、`MetadataService.java:473-484` | ① `executeProcess` 先读完 stdout 才进入 `waitFor(timeout)`，超时机制形同虚设；② 音频提取和元数据清理的 FFmpeg 命令缺少 `-y`/`-nostdin`，目标文件已存在时会卡在覆盖确认提示；③ 压缩/转换/水印没有任何超时 | 高 | 工作线程永久挂死，FFmpeg 进程泄漏；连续触发会耗尽线程池，导致服务不可用 | 统一的 `ProcessRunner`：独立线程读取输出、强制超时、`destroyForcibly` 结束进程树、关闭 stdin、加 `-nostdin -y` | **已复现**：同一文件第二次提取音频后任务永久停在 `PENDING`，需手动结束 ffmpeg 进程 | 已修复 |
| H-05 | `application.properties:13-14`、`VideoCompressorBackendApplication.java:8` | 上传上限 5000MB；`@Async` 使用默认执行器（8 线程 + 无界队列）；没有用户级并发限制和磁盘空间检查 | 高 | 少量请求即可占满 CPU、内存和磁盘，造成拒绝服务 | 有界线程池（默认 2 路 FFmpeg 并发）+ 有界队列（满时返回 429）；用户级并发配额；任务前检查磁盘空间；按媒体类型分别设置体积上限 | 代码 | 已修复 |
| H-06 | `ImageController.java:31`、`MetadataController.java:28`、`VideoService.java:49` | 不校验真实文件类型；ImageMagick 会处理任意内容（SVG 外部引用、MSL/MVG 等危险 coder），FFmpeg 会处理任意内容（例如伪装成视频的 HLS 播放列表可读取本地文件或发起网络请求） | 高 | 通过恶意媒体文件造成 SSRF 或本地文件读取 | 用魔数检测真实类型并按接口设白名单；扩展名以检测结果为准；SVG 拒绝外部引用；ImageMagick 使用受限 `policy.xml` 并显式指定输入 coder；FFmpeg 使用 `-protocol_whitelist file` 与容器格式白名单 | 代码 + 已知攻击类别；修复后用测试验证拦截 | 已修复 |
| H-07 | `TaskService.java:13`、`ImageService.java:70-72`、`PdfService.java:47` | ① 任务只存在内存中，重启全部丢失，且永不清除；② 部分空指针发生在 `try` 之外，任务永久停在 `PENDING` | 高 | 状态丢失；前端无限轮询；内存持续增长 | 任务持久化到 SQLite；启动时把中断的任务标记为失败且可重试；所有异常在任务边界统一捕获 | **已复现**：`{"tool":"compress"}` 缺少 `filenames` 时任务永久为 `PENDING` | 已修复 |

### 2.2 中危

| ID | 文件位置 | 问题 | 严重程度 | 影响 | 修改建议 | 证据 | 状态 |
|---|---|---|---|---|---|---|---|
| M-01 | `MetadataController.java:50,63`、`ImageController.java:41`、`MetadataService.java:619` | 异常信息原样返回客户端，包括服务器绝对路径和命令输出 | 中 | 泄露内部路径和环境信息 | 全局异常处理 + 统一错误码；详细信息只写日志 | **已复现**：上传失败响应中包含 `C:\Users\...\up\...` | 已修复 |
| M-02 | 全部 DTO | 没有参数校验：crf 越界被静默替换为 23；opacity/margin/width/height/quality/density 没有范围限制（density 可设为 100000，导致 Ghostscript 内存耗尽） | 中 | 结果与预期不符；资源耗尽 | Bean Validation 加上明确的范围，越界返回 400 并给出字段级错误 | 代码 | 已修复 |
| M-03 | `VideoService.java:156-164`、`VideoService.java:356-365` | 进度和码率计算依赖客户端传入的 `duration`/`audioBitrate`；`duration=0` 时进度变为 `(int)Infinity = 2147483647`，码率计算出现除零 | 中 | 进度失真；可被构造的请求影响 | 时长、码率一律以服务端 ffprobe 的结果为准；进度限制在 0–100 | 代码 + 单元测试 | 已修复 |
| M-04 | `VideoService.java:119-147`、`CompressRequestDTO.java:21-22` | `width`/`height` 从未使用（分辨率参数无效）；bitrate 模式单遍编码、不限 maxrate，无法保证目标大小；不支持 H.265、帧率、音频参数 | 中 | 专业参数名不副实；目标大小可能超标 | 实现缩放（不放大、偶数尺寸）、H.264/H.265、帧率、音频码率；bitrate 模式使用两遍编码；提交前做可行性评估 | 代码 | 已修复 |
| M-05 | `AudioService.java:72-77` | `aac` 和未知格式默认使用 `-acodec copy`，源音轨不是 AAC 时必定失败；未知格式会生成任意扩展名的文件 | 中 | 功能失败 | 格式白名单；仅当源音轨为 AAC 时才 copy，否则重新编码 | 代码 | 已修复 |
| M-06 | `VideoService.java:113`、`AudioService.java:46`、`MetadataService.java:469,501`、`ImageService.java:152` | 输出文件名由输入名确定性生成，同一输入的并发任务会互相覆盖 | 中 | 结果错乱 | 每个任务使用独立工作目录，输出名基于 UUID | 代码 | 已修复 |
| M-07 | 全局 | 上传、输出和临时文件从不清理；失败时残留临时目录（`PdfService.java:58`）和中间文件 | 中 | 磁盘被占满 | 定时清理过期文件；任务结束时用 `finally` 删除工作目录 | 代码 | 已修复 |
| M-08 | `application.properties`、`CorsConfig.java` | 硬编码 `C:/`、`D:/` 绝对路径和 CORS 域名；没有多环境配置 | 中 | 换一台电脑就无法运行 | 配置外置（`.env` / 环境变量），给出合理默认值，工具路径默认从 PATH 查找 | 代码 | 已修复 |
| M-09 | `TaskService.java:42` | 所有任务完成时都显示「压缩成功！」；失败时不记录阶段 | 中 | 状态信息误导用户 | 按任务类型生成消息，记录错误码 | **已复现**（音频提取、转换都返回「压缩成功！」） | 已修复 |
| M-10 | 全局 | 不支持取消、重试和幂等；重复点击会创建重复任务 | 中 | 资源浪费，体验差 | 新增取消/重试接口；支持 `Idempotency-Key` 请求头 | 代码 | 已修复 |
| M-11 | `MetadataService.java:611`、`ImageService.java:193` | 外部进程输出按平台默认编码（中文 Windows 为 GBK）解码；`identify -format` 的告警被当作数据返回（`"identify": "unknown image property"`） | 中 | 中文元数据乱码；结果中出现脏数据 | 进程 I/O 显式指定 UTF-8；元数据读取改用 `exiftool -json -n -G1` | **已复现** | 已修复 |
| M-12 | `VideoService.java:149,159` | INFO 级别日志打印完整命令；DEBUG 级别逐行打印 FFmpeg 输出；日志非结构化 | 中 | 日志噪声大、可能泄露路径 | 结构化日志（带 taskId 的 MDC），命令仅在 DEBUG 级别输出 | 代码 | 已修复 |
| M-13 | `PdfService.java` | 由 Ghostscript 解析不可信 PDF（历史上有多个 RCE 漏洞） | 中 | 依赖漏洞风险 | 保持 `-dSAFER`（ImageMagick 默认开启）；policy 中限制资源；部署文档要求使用最新版 Ghostscript（当前 10.08.0） | 代码 | 已缓解（接受残余风险） |
| M-14 | 全局 | 没有健康检查、API 文档和依赖工具自检 | 中 | 部署后难以排查 | 新增 Actuator health + 工具可用性检查，以及 OpenAPI 文档 | 代码 | 已修复 |
| M-15 | `src/test` | 只有一个 `contextLoads` 测试 | 中 | 回归无保障 | 补充单元测试、MockMvc、属性测试和真实媒体集成测试 | 代码 | 已修复 |

### 2.3 低危

| ID | 文件位置 | 问题 | 严重程度 | 影响 | 修改建议 | 状态 |
|---|---|---|---|---|---|---|
| L-01 | `ImageService.java:151,279`、`MetadataService.java` | 文件名没有扩展名时 `substring(lastIndexOf('.'))` 抛出 `StringIndexOutOfBoundsException` | 低 | 任务失败且提示不明确 | 统一的文件名工具方法 | 已修复 |
| L-02 | `MetadataService.java:384` | GPS 解析失败时返回 `0.0`，显示出一个虚假坐标 (0,0) | 低 | 误导用户 | 解析失败时不输出该字段 | 已修复 |
| L-03 | `MetadataService.java:601` | 仅按扩展名判断音视频，缺少 webm/aac/ogg/m4v 等格式 | 低 | 部分格式无法处理 | 以上传时检测到的媒体类型为准 | 已修复 |
| L-04 | 三个 Controller | 三套几乎相同的上传实现，四个相同的进度接口 | 低 | 维护成本高 | 统一 `FileStorageService` 与任务查询 | 已修复 |
| L-05 | `VideoService.java:74-77` | ffprobe 失败时静默返回全 0 的信息，并保留无效的上传文件 | 低 | 前端显示错误信息 | 探测失败时返回 422 并删除文件 | 已修复 |
| L-06 | `pom.xml` | `devtools` 被打进运行时依赖；缺少 validation/security/actuator | 低 | — | 调整依赖 | 已修复 |

## 3. 健壮性 / 工程化 / 性能专项检查结论

| 维度 | 检查项 | 原始状态 | 处理 |
|---|---|---|---|
| 健壮性 | 统一异常处理 | 无 | `GlobalExceptionHandler` + `ErrorCode` |
| 健壮性 | 事务边界 | 无数据库 | 状态变更方法使用 `@Transactional`，进度更新做节流 |
| 健壮性 | 并发任务冲突 | 输出互相覆盖 | 每个任务独立工作目录（M-06） |
| 健壮性 | 大文件上传中断 | Tomcat 残留临时文件 | 使用 multipart 临时目录 + 写入失败时删除半成品 |
| 健壮性 | 进程异常终止后的恢复 | 无 | 启动时把 QUEUED/RUNNING 任务标记为 FAILED（`INTERRUPTED`，可重试） |
| 工程化 | API 文档 | 仅 README | springdoc OpenAPI（`/swagger-ui.html`）+ `docs/api-contract.md` |
| 工程化 | 依赖漏洞 | 未检查 | Spring Boot 3.5.7 管理的依赖；外部工具使用 2026-09 的最新版本 |
| 性能 | 数据库索引 / N+1 / 分页 | 无数据库 | `tasks(owner_id, created_at)`、`media_files(owner_id, kind)` 建索引；列表接口分页 |
| 性能 | 文件流 | `transferTo` 流式写入，下载返回 `Resource` | 保留；下载支持 HTTP Range（视频预览可拖动） |
| 性能 | FFmpeg 并发 | 最多 8 个，无限排队 | 默认 2 路并发，可配置；FFmpeg `-threads` 限制 |
| 性能 | 临时磁盘 | 不检查 | 任务前检查可用空间 |

## 附录 A：复现脚本

复现时使用 `java -jar` 启动原始 JAR，上传和输出目录指向隔离的临时目录。关键请求如下：

```text
H-01  POST /api/video/convert  {"uniqueFilename":"a.mp4","targetFormat":"x\\..\\..\\PWNED.mp4","duration":2}
      → 任务 COMPLETED，PWNED.mp4 出现在输出目录的上两级目录中
H-02  POST /api/metadata/view  {"uniqueFilename":"..\\secret.png"}
      → 200，返回上传目录外 secret.png 的 "64 x 64 / 515B"
H-03  GET  /api/video/download/compressed_victim.mp4（不带任何凭据）→ 200
H-04  POST /api/audio/extract ×2（同一文件，mp3）→ 第二个任务永久 PENDING，ffmpeg 进程常驻
H-07  POST /api/image/process {"tool":"compress"} → 任务永久 PENDING（异步线程抛出 NPE）
M-01  multipart 上传，filename="../PLANTED_A.txt" → 500，响应体包含服务器绝对路径
```

未能复现（如实记录）：
- `GET /api/video/download/..%5Csecret.txt`、`..%2F` 被 Tomcat 以 400 拦截，URL 路径穿越**不成立**。
- 上传文件名携带 `../` 时，由于中间目录 `uuid_..` 不存在，Java 写入失败，上传路径穿越在 Windows 上**未能写入**（但会触发 M-01 的信息泄露）。

## 4. 修复验证对照

每项修复都由自动化测试（`backend/src/test`，结果见 [`test-report.md`](test-report.md)）或真实运行验证；下表列出对应关系。

| ID | 验证方式 |
|---|---|
| H-01 | `ApiSecurityTests.invalidParametersAreRejectedWithFieldErrors`（`targetFormat` 含 `..\` 时返回 400）；`StorageNameProperties`（5000 组随机输入下，清洗后的名称都不含路径分隔符） |
| H-02 | `ApiSecurityTests.pathLikeIdentifiersNeverReachTheFileSystem`（4 种路径形式的 ID 都返回 404） |
| H-03 | `ApiSecurityTests.usersCannotSeeOrUseEachOthersFilesAndTasks`（10 个越权访问点）、`protectedEndpointsRequireToken`、`forgedOrMalformedTokensAreRejected`；`SecurityPrimitivesTest`（票据与文件绑定、过期失效、防篡改） |
| H-04 | `ProcessRunnerTest`（超时后进程树被结束、取消立即生效、关闭 stdin 后不会卡死）；`MediaPipelineTests.audioExtractionAllFormatsAndRepeatable`（同一文件连续提取 2 次都成功） |
| H-05 | `ApiSecurityTests.perUserActiveTaskLimitIsEnforced`（第 5、6 个任务返回 429）；有界线程池、有界队列与磁盘检查见 `TaskEngine` |
| H-06 | `FileTypeDetectorTest`（按内容判定类型；SVG 的外部引用、脚本、DTD 被拒绝；文本和 EXE 被拒绝）、`ApiSecurityTests.uploadsAreCheckedByContentAndEndpoint` |
| H-07 | `MediaPipelineTests.tasksLeftRunningByACrashAreRecoveredAsRetryableFailures`；`ApiSecurityTests.handlerValidationRejectsMissingFieldsInsteadOfLeavingTasksStuck` |
| M-01 | `ApiSecurityTests.errorResponsesNeverLeakServerPaths` |
| M-02 | `ApiSecurityTests.invalidParametersAreRejectedWithFieldErrors` |
| M-03 | `FfmpegProgressProperties`（3000 组随机输入，含 0、NaN、∞，进度都在区间内且单调） |
| M-04 | `MediaPipelineTests.crfCompressionWithDownscale…`、`legacyWidthHeightNeverUpscale`、`targetSizeIsMetWithTwoPassForBothCodecs`（H.264/H.265 实测 ≤ 1.5MB）、`unreachableTargetIsRefusedWithSuggestionNotFaked`；`CompressionPlannerProperties` |
| M-05 | `MediaPipelineTests.audioExtractionAllFormatsAndRepeatable`（5 种格式由 ffprobe 独立核验编码器） |
| M-06 / M-07 | 每个任务使用独立工作目录，结束后删除（`cancelAndRetryRunningTask` 断言工作目录已清理）；`expiredFilesArePurged` |
| M-08 | 冒烟测试：不做任何配置即可启动，6 个外部工具全部自动发现 |
| M-10 | `cancelAndRetryRunningTask`；`concurrentSubmitsWithSameIdempotencyKeyCreateOneTask` |
| M-11 | `metadataEditReadBackAndVerifiedScrub`（中文标题和作者写入后原样读回） |
| M-13 | 已缓解，接受残余风险：页面尺寸异常巨大的恶意 PDF 仍可能让 Ghostscript 长时间渲染，由任务超时、取消、并发上限与用户配额兜底 |
| M-14 | `/actuator/health` 中的 `mediaTools` 指标；`ApiSecurityTests.systemCapabilitiesArePublicAndSecretFree` |

## 5. 整改过程中新发现并已修复的问题

| ID | 位置 | 问题 | 严重程度 | 处理 | 验证 |
|---|---|---|---|---|---|
| N-01 | `FileTypeDetector` | Tika 无法从内容识别时会回退到文件扩展名，内容为空、名为 `x.png` 的文件会被当作 PNG | 高 | 只按内容检测；MP4 封装中只含音轨时，经 ffprobe 确认后归为音频 | `FileTypeDetectorTest.rejectsTextAndExecutables` |
| N-02 | SQLite 连接池 | WAL 模式下读事务升级为写事务时会立即返回 `SQLITE_BUSY`，不等待 `busy_timeout`，并发写入出现 500 | 中 | 改为单连接串行访问（演示规模足够） | 并发相关测试全部通过 |
| N-03 | `TaskEngine.submit` / `AuthService.register` | 相同幂等键或用户名并发提交时，唯一索引冲突被 SQLite 方言报为通用 `JpaSystemException`，未被捕获，返回 500 | 中 | 捕获后查询胜出的那条记录并返回 | `concurrentSubmitsWithSameIdempotencyKeyCreateOneTask`、`concurrentRegistrationOfSameNameYieldsOneAccount` |
| N-04 | `TaskEngine.submit`（审计开始前中断的那次会话生成的代码） | 在事务尚未提交时就把任务放入队列，执行线程可能读不到这条任务，任务永远停在 QUEUED | 高 | `submit` 不再包在事务中，保存即提交 | 所有任务类测试 |
| N-05 | `RemoveBackgroundHandler` | rembg 2.0.85 的默认模型约 1GB，ONNX Runtime 加载时内存分配失败 | 中 | 显式使用 `u2net` 模型（可通过 `REMBG_MODEL` 配置） | `backgroundRemovalWithRembg` |
| N-06 | `PdfHandler` 图片合并 | 带 1:1 JFIF 密度的 JPEG 被 ImageMagick 视为 1 DPI，生成约 1600 英寸宽的页面，后续 PDF 转图片会长时间渲染 | 中 | 合并时统一设置 150 DPI | `pdfRoundTrip` |
