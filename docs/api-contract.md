# API 契约

基础地址：`http://localhost:8080`　·　交互式文档：`/swagger-ui.html`（OpenAPI：`/v3/api-docs`）

## 1. 通用约定

| 项 | 约定 |
|---|---|
| 编码 | 请求与响应均为 UTF-8 JSON；上传使用 `multipart/form-data`，字段名为 `file` |
| 鉴权 | 除注册、登录、`/api/system/**`、`/actuator/health` 外，全部接口需要 `Authorization: Bearer <JWT>` |
| 文件句柄 | `uniqueFilename` / `downloadFilename` / `fileId` 是同一种**不透明 ID**（UUID）。服务端只按 ID 和归属查找文件，客户端无法指定路径 |
| 归属 | 文件和任务都属于创建者；访问他人资源一律返回 `404`，不透露资源是否存在 |
| 幂等 | 所有创建任务的接口都支持 `Idempotency-Key` 请求头：同一用户使用相同 Key 重复提交时，返回同一个任务 |
| 可信数据 | 时长、码率、分辨率一律以服务端 ffprobe 结果为准；原契约中的 `duration`、`audioBitrate` 字段仍可传入，但会被忽略 |

### 错误格式

```json
{ "code": "VALIDATION_FAILED", "message": "参数校验失败", "details": { "fields": { "crfValue": "最大不能超过51" } } }
```

| HTTP | code | 含义 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 参数不合法（含字段级明细） |
| 401 | `UNAUTHORIZED` / `BAD_CREDENTIALS` | 未登录、令牌无效、用户名或密码错误 |
| 404 | `NOT_FOUND` | 资源不存在或无权访问 |
| 409 | `USERNAME_TAKEN` / `INVALID_TASK_STATE` | 用户名冲突；当前任务状态不允许该操作 |
| 413 | `FILE_TOO_LARGE` | 超过体积上限 |
| 415 | `UNSUPPORTED_MEDIA` | 真实文件类型不被该接口接受 |
| 422 | `MEDIA_UNREADABLE` / `TARGET_UNREACHABLE` / `CAPABILITY_UNSUPPORTED` | 媒体无法解析；目标无法实现（附建议）；格式不支持该操作 |
| 429 | `TOO_MANY_TASKS` / `RATE_LIMITED` | 进行中的任务超过配额；登录失败过多 |
| 503 | `QUEUE_FULL` / `TOOL_UNAVAILABLE` / `INSUFFICIENT_DISK` / `AI_UNAVAILABLE` | 队列已满；依赖工具未安装；磁盘空间不足；AI 未配置 |
| 500 | `INTERNAL_ERROR` | 附带 `traceId`，详细信息只写入服务端日志 |

### 任务对象（TaskView）

所有 `progress` 查询接口、`/api/tasks/**` 以及 SSE 事件都返回同一结构：

```json
{
  "taskId": "8c1e…", "type": "video.compress",
  "status": "QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELLED",
  "progress": 63, "stage": "encoding", "message": "正在处理...",
  "errorCode": null,
  "downloadFilename": "f3a9…",
  "output": { "fileId": "f3a9…", "filename": "demo_compressed.mp4", "sizeBytes": 1520331, "mediaType": "VIDEO", "downloadUrl": "/api/files/f3a9…/content" },
  "result": { "originalSize": 4631567, "outputSize": 1520331, "savedPercent": 67.2 },
  "origin": "PRO | AI", "attempt": 1,
  "createdAt": 1791727183019, "startedAt": 1791727183023, "finishedAt": 1791727190000
}
```

- `progress` 来自 FFmpeg `-progress` 输出中的真实时间位置（两遍编码时，第一遍映射到 0–45%，第二遍映射到 45–100%）；无法给出百分比的工具只报告阶段（`stage`）。
- 与原契约的差异：状态值由 `PENDING/PROCESSING/COMPLETED/FAILED` 改为上表的五种状态（已经过产品确认）。

## 2. 认证 `/api/auth`

| 方法 | 路径 | 请求 | 响应 |
|---|---|---|---|
| POST | `/register` | `{username(3-32), password(8-72), displayName?}` | `{token, expiresIn, user{id,username,displayName}}` |
| POST | `/login` | `{username, password}` | 同上；同一 IP + 用户名 10 分钟内失败 8 次后返回 429 |
| GET | `/me` | — | `{id, username, displayName}` |

## 3. 文件 `/api/files`（新增）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/upload` | 接受视频、音频、图片、PDF，返回 `FileView`（含 ffprobe 摘要 `info`） |
| GET | `?role=INPUT\|OUTPUT&page&size` | 分页列表 |
| GET | `/{id}` | 单个文件 |
| DELETE | `/{id}` | 删除 |
| POST | `/{id}/ticket` | 签发 10 分钟有效、只能访问该文件的票据：`{ticket, url, downloadUrl, expiresAt}` |
| GET | `/{id}/content?ticket=&download=1` | 流式读取，支持 HTTP Range（视频可拖动）。响应带 `CSP: sandbox` 与 `nosniff`，SVG 一律以附件下载 |

上传限制：类型由文件内容（魔数）判定；图片不超过 50MB，PDF 不超过 200MB，音频不超过 1GB，视频受 `APP_MAX_UPLOAD_MB`（默认 2048）限制。SVG 不得包含 DTD、脚本或外部引用。ffprobe 无法读取的音视频会被拒绝（422），且文件不会保留。

## 4. 视频 `/api/video`（原路径）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/upload` | 原契约 `{videoInfo{originalFilename, formatName, duration, size, width, height, videoBitrate, audioBitrate}, uniqueFilename}`，新增 `fps / videoCodec / audioCodec / hasAudio / file` |
| POST | `/compress` | 见下表 → `{taskId, status}` |
| POST | `/compress/estimate` | 新增：目标体积可行性评估（不执行处理）→ `{feasible, videoKbps, height, bpp, minFeasibleBytes, reason, suggestions[]}` |
| POST | `/convert` | `{uniqueFilename, targetFormat: mp4\|mov\|webm\|mkv\|gif, fps?}` |
| POST | `/watermark` | `{videoUniqueFilename, watermarkUniqueFilename, position(9 选 1), opacity 0-1, margin 0-500}` |
| GET | `/progress/{taskId}` | TaskView |
| GET | `/download/{filename}` | 原路径；需要 Bearer 令牌或 `?ticket=` |

`/compress` 请求字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `uniqueFilename` | string | 必填 |
| `mode` | `crf` \| `bitrate` \| `size` \| `kbps` | `bitrate` 保持原含义（按 `targetSizeMB` 压缩），`size` 为其别名；`kbps` 表示直接指定 `videoBitrateKbps` |
| `crfValue` | 0-51 | crf 模式，默认 23 |
| `targetSizeMB` | >0 | 两遍编码；无法实现时任务以 `TARGET_UNREACHABLE` 失败，并给出建议 |
| `resolution` | `original\|2160p\|1440p\|1080p\|720p\|540p\|480p\|360p` | 只缩小、不放大 |
| `width` / `height` | 0-7680 | 原字段；大于等于源分辨率时不做处理 |
| `codec` | `h264\|h265` | 默认 h264 |
| `fps` | 1-120 | |
| `audioBitrateKbps` | 0 或 32-512 | 0 表示去除音轨；默认 128（AAC） |
| `videoBitrateKbps` | 50-200000 | `kbps` 模式使用 |
| `preset` | `ultrafast…slow` | 默认 medium |

## 5. 音频 `/api/audio`

`POST /extract`：`{uniqueFilename, targetFormat: mp3|aac|m4a|wav|flac, bitrateKbps? 32-320, sampleRate? (8000/16000/22050/32000/44100/48000/96000), channels? 1|2}`；`GET /progress/{taskId}`。

## 6. 图片 `/api/image`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/upload` | 接受图片和 PDF（PDF 工具原本没有独立的上传接口） |
| POST | `/process` | `{filenames[1-50], tool, settings}` |
| POST | `/remove-background` | `{uniqueFilename}`（rembg） |
| GET | `/progress/{taskId}` | |

`tool` 与 `settings`：

| tool | settings |
|---|---|
| `convert` | `targetFormat: jpg\|png\|webp\|avif\|gif\|bmp\|tiff`, `quality 1-100` |
| `resize` | `width` / `height`（至少填一个）, `keepAspectRatio`（默认 true） |
| `compress` | `format: keep\|jpg\|webp\|avif\|png`, `quality`, `targetSizeKB`（JPG/WebP/AVIF 按质量二分搜索）, `maxWidth` / `maxHeight`（只缩小） |

结果：单张图片直接输出该图片；多张时输出 zip。`result.items[]` 中每一项都带有 `outputFileId`、`inputSize`、`outputSize`，设置了目标体积时还带 `targetMet` 和 `suggestion`。

## 7. PDF `/api/pdf`

`POST /process`：`tool = pdf-to-image`（`density 72-600, format png|jpg, quality`，输出 zip）或 `images-to-pdf`（图片按传入顺序合并，最多 200 张）。

## 8. 元数据 `/api/metadata`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/upload` | 接受图片和音视频，返回中额外带 `capability`（该格式可写字段） |
| POST | `/view` | `{uniqueFilename}` → `{groups{组名:{标签:值}}, sensitiveTags[], hasPrivacyData, gps{latitude, longitude, mapUrl}?}`（ExifTool） |
| POST | `/remove` | `{uniqueFilename, mode?: all\|location}` → 同步返回 `{downloadFilename, taskId, result{verified, remainingLocationTags[]}}`；处理时间超过 90 秒时返回 202 + taskId |
| POST | `/modify` | `{uniqueFilename, title?, artist?, comment?, copyright?, make?, model?, dateTimeOriginal?, gpsLatitude?, gpsLongitude?, clearGps?}` → 同步返回 `{downloadFilename, taskId, result{writtenFields[]}}` |
| GET | `/capabilities` | 各格式可写字段矩阵 |

可写字段（以 `MetadataCapabilities` 为准）：

| 格式 | 写入工具 | 可写字段 | 仅清除位置信息 |
|---|---|---|---|
| jpg / png / webp / heic / avif / tiff | ExifTool | 全部 8 项 | ✅ |
| mp4 / mov / m4v / m4a | ExifTool (QuickTime) | 全部 8 项 | ✅ |
| mkv / webm / mp3 / flac / ogg / wav / avi | FFmpeg | title, artist, comment, copyright, dateTimeOriginal | ❌（只支持全部清除） |
| gif | ExifTool | title, artist, comment, copyright | ✅ |
| svg / bmp | — | 不支持 | — |

请求格式不支持的字段时返回 `422 CAPABILITY_UNSUPPORTED`，`details` 中列出 `unsupportedFields` 与 `editableFields`，不会静默忽略。

## 9. 统一任务 `/api/tasks`（新增）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/` | `{type, inputs[fileId], params}` 直接创建任务（`type` 见 `GET /types`） |
| GET | `/?page&size` | 当前用户的任务（按时间倒序） |
| GET | `/{id}` | TaskView |
| POST | `/{id}/cancel` | QUEUED 状态立即取消；RUNNING 状态结束整个进程树。已结束的任务返回 409 |
| POST | `/{id}/retry` | 仅限 FAILED/CANCELLED 状态，`attempt + 1` |
| GET | `/{id}/events` | SSE：先推送一次 `status` 快照，之后推送 `progress` / `status`；任务结束时关闭连接；每 15 秒发送一次心跳注释。断线重连或刷新页面后会重新获得当前状态 |

## 10. 系统

- `GET /api/system/capabilities`（公开）：工具可用性、功能开关、限制参数、AI 是否已配置（只返回布尔值）、元数据能力矩阵
- `GET /actuator/health`：包含 `mediaTools` 指标（各工具状态与剩余磁盘空间）

## 11. 智能模式 `/api/ai`（新增）

DeepSeek 只在后端调用，浏览器不会接触到 Key。未配置 Key 时，`POST /messages` 返回 `503 AI_UNAVAILABLE`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/conversations` | 新建会话 → `ConversationView` |
| GET | `/conversations?page&size` | 当前用户的会话列表 |
| GET | `/conversations/{id}` | 快照：`{conversation, messages[], events[]}`，events 按 `seq` 升序 |
| POST | `/conversations/{id}/messages` | `{text(1-2000), fileIds?(≤10，必须属于当前用户)}`，开始一次运行；会话正在处理时返回 409 |
| POST | `/conversations/{id}/confirm` | `{version, approve}`：只有 `AWAITING_CONFIRMATION` 状态且版本号等于当前计划版本才执行，否则返回 409 |
| POST | `/conversations/{id}/cancel` | 取消当前运行，并取消其中正在执行的任务 |
| GET | `/conversations/{id}/events?after=<seq>` | SSE；也接受 `Last-Event-ID` 请求头，只补发 seq 之后的事件 |
| GET | `/agents` | Agent 列表：编号、名称、SKILL 版本、工具白名单、当前 prompt 版本 |

`ConversationView`：`{id, title, state, planVersion, plan, context{attachedFileIds, requirements, review}, promptVersion, createdAt, updatedAt}`

`state`：`IDLE / ANALYZING / AWAITING_INPUT / PLANNING / AWAITING_CONFIRMATION / EXECUTING / REVIEWING / COMPLETED / FAILED / CANCELLED`

### 事件类型（SSE `event:` 字段，`id:` 为 seq）

| 事件 | 持久化 | payload |
|---|---|---|
| `state` | ✅ | `{state}` |
| `message` | ✅ | `{id, role: user\|assistant, agent?, content, createdAt}` |
| `text.delta` | ❌（只实时推送） | `{agent, delta}`：模型的流式文字，最终内容以随后的 `message` 为准 |
| `agent` | ✅ | `{agent, status: working\|waiting\|done\|partial\|failed\|cancelled, note}` |
| `tool.start` / `tool.done` | ✅ | `{agent, callId, name, args, ok, summary?}` |
| `requirements` | ✅ | Agent 01 提交的结构化需求（含 `clarifications[].options`，前端显示为快速回复） |
| `thought` | ✅ | 模型没有调用工具、直接输出的文字（用于排查） |
| `plan` | ✅ | `{version, summary, steps[{id, tool, title, inputs[], params, rationale, qualityImpact, estimate?}], warnings[]}` |
| `confirm.required` / `confirmed` | ✅ | `{version}` |
| `step` | ✅ | `{stepId, status: QUEUED\|RUNNING\|SUCCEEDED\|FAILED\|CANCELLED\|SKIPPED, taskId?, progress?, stage?, outputFileId?, message?}` |
| `review` | ✅ | `{checks[{stepId, name, ok, expected, actual, afterRetry?}], retries, allPassed, outputs[{stepId, tool, status, fileId, filename, sizeBytes}]}` |
| `usage` | ✅ | `{model, llmCalls, promptTokens, completionTokens}` |
| `error` | ✅ | `{code, message}` |

计划步骤里的 `tool` 与 `/api/tasks` 的任务类型一一对应，`params` 与专业模式的参数相同（见第 4–8 节）；`inputs` 中的 `"step:N"` 表示使用第 N 步的输出文件。
