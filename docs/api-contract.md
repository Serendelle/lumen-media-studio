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
| 422 | `TARGET_NOT_MET` / `AUDIO_STREAM_MISSING` / `OUTPUT_INVALID` | V2：编码完成但实测字节仍超出目标（已自动降码率重试 1 次）；输入没有音轨；输出未通过独立核验。任务失败时 `result.verification` 保留核验明细 |
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
  "createdAt": 1791727183019, "startedAt": 1791727183023, "finishedAt": 1791727190000,
  "inputs": [{ "fileId": "6e13…", "filename": "样例课程.mp4", "mediaType": "VIDEO" }]
}
```

- `inputs`（V2 新增）：按提交顺序列出输入文件；文件过期或被删除后只保留 `fileId`，不再返回 `filename`。

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
| `targetSizeMB` | >0 | **单位为 MiB**（1 MiB = 1,048,576 字节，字段名沿用旧称）。两遍编码；评估不可行时以 `TARGET_UNREACHABLE` 失败并给出建议；编码后按真实字节核对，超出时降码率重试 1 次，仍超出则以 `TARGET_NOT_MET` 失败 |
| `resolution` | `original\|2160p\|1440p\|1080p\|720p\|540p\|480p\|360p` | 只缩小、不放大 |
| `width` / `height` | 0-7680 | 原字段；大于等于源分辨率时不做处理 |
| `codec` | `h264\|h265` | 默认 h264 |
| `fps` | 1-120 | |
| `audioBitrateKbps` | 0 或 32-512 | 0 表示去除音轨；默认 128（AAC） |
| `videoBitrateKbps` | 50-200000 | `kbps` 模式使用 |
| `preset` | `ultrafast…slow` | 默认 medium |

## 5. 音频 `/api/audio`

`POST /extract`：`{uniqueFilename, targetFormat: mp3|aac|m4a|wav|flac, bitrateKbps? 32-320, sampleRate? (8000/16000/22050/32000/44100/48000/96000), channels? 1|2}`；`GET /progress/{taskId}`。

- `uniqueFilename` 可以是上传文件，也可以是处理结果（如压缩后的视频）；服务端只使用这个文件，不会替换成别的版本。
- 已知没有音轨的输入直接返回 422 `AUDIO_STREAM_MISSING`，不创建任务，也不生成空文件。
- `result.operation`：`transcode`（MP3/AAC/M4A，重新编码）或 `extract-lossless`（WAV/FLAC）；`result.sourceAudioCodec` 为源音轨编码。

### 输出核验（V2）

`video.compress`、`video.convert`、`audio.extract` 成功时，`result.verification` 为：

```json
{ "status": "VERIFIED",
  "checks": [{ "name": "目标体积", "ok": true, "expected": "≤ 0.30 MiB（314572 字节）", "actual": "0.30 MiB（309635 字节）" }],
  "measured": { "actualBytes": 309635, "targetBytes": 314572, "formatName": "mov,mp4,…", "duration": 6.0,
                "videoStreams": 1, "audioStreams": 1, "videoCodec": "h264", "width": 640, "height": 360, "fps": 25 } }
```

数据全部来自对输出文件的重新检测：磁盘上的真实字节数、新一轮 FFprobe，以及首尾各约 2 秒的实际解码（必须真正解出画面，退出码为 0 不算通过）。规则：

| 操作 | 检查项 |
|---|---|
| `video.compress` | 目标体积（size 模式）、视频编码、分辨率上限、音轨保留/去除、画面可解码 |
| `video.convert` | 封装格式（MP4 与 MOV 用 major brand 区分）、视频流与编码、音轨（GIF 要求无音轨；其它格式跟随源文件）、画面可解码 |
| `audio.extract` | 音频流 ≥ 1、视频流 = 0、音频编码、采样率/声道（若指定）、时长 > 0 |

任一检查不通过，任务以对应错误码失败（`TARGET_NOT_MET` 或 `OUTPUT_INVALID`）；智能模式的质量审核独立使用同一套规则重新检查。

## 6. 图片 `/api/image`

V2：批量处理时单张失败不会让整批失败。`result` 新增 `succeeded`、`failed`、`overall`（`SUCCEEDED` | `PARTIAL_SUCCESS`，展示层汇总，任务状态仍为 `SUCCEEDED`）；失败项为 `{ok:false, errorCode, error}`，没有 `outputFileId`；下载的 zip 只包含成功的文件（文件名为 `images_processed_partial.zip`）。全部失败时任务为 `FAILED`。


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
| GET | `/?page&size&status` | 当前用户的任务（按时间倒序）；`status` 可选，逗号分隔，如 `QUEUED,RUNNING`，未知状态返回 400 `VALIDATION_FAILED` |
| GET | `/{id}` | TaskView |
| POST | `/{id}/cancel` | QUEUED 状态立即取消；RUNNING 状态结束整个进程树。已结束的任务返回 409 |
| POST | `/{id}/retry` | 仅限 FAILED/CANCELLED 状态，`attempt + 1` |
| GET | `/{id}/events` | SSE：先推送一次 `status` 快照，之后推送 `progress` / `status`；任务结束时关闭连接；每 15 秒发送一次心跳注释。断线重连或刷新页面后会重新获得当前状态 |

## 10. 系统

- `GET /api/system/capabilities`（公开）：工具可用性、功能开关、限制参数、AI 是否已配置（只返回布尔值）、元数据能力矩阵
- `GET /actuator/health`：包含 `mediaTools` 指标（各工具状态与剩余磁盘空间）

## 11. 工作台概览 `/api/v2/workspace`（V2）

V2 新业务接口统一放在 `/api/v2` 下，原有 V1 路径保持不变。

- `GET /api/v2/media/compare?sourceFileId&outputFileId`（需登录，两个文件都必须属于当前用户，否则 404）：`{source, output, deltaBytes, savedPercent?, durationDeltaMs?}`，每侧为 `{fileId, filename, mediaType, sizeBytes, readable, measured{…同上}}`，全部为当场检测的数据。
- `GET /api/v2/workspace/summary`（需登录）：`{tasksByStatus{QUEUED,RUNNING,SUCCEEDED,FAILED,CANCELLED}, inputs{count,bytes}, outputs{count,bytes}, nextExpiryAt?, recentTasks[TaskView ×5], recentFiles[FileView ×5]}`。全部数字由数据库按当前用户聚合，不含任何其他用户的数据。

## 12. 文档工作台 `/api/v2/documents`（V2）

文档通过 `POST /api/files/upload` 上传，`mediaType` 为 `DOCUMENT`（PDF 仍为 `PDF`），`info` 含 `format` 与 `sha256`。上传检查：

| 输入 | 限制与校验 |
|---|---|
| `.docx` / `.xlsx` | ≤ 30 MB；按 OOXML 包结构判断真实格式（必须有 `[Content_Types].xml` 与 `word/document.xml` 或 `xl/workbook.xml`，并与扩展名一致）；条目 ≤ 5000、解压后 ≤ 原大小 100 倍且 ≤ 250 MiB、无 `../`/绝对路径；拒绝宏（`.docm/.xlsm`、`vbaProject.bin`）、DTD/实体、会主动加载外部内容的关系（超链接除外） |
| `.html` | 单文件、≤ 5 MB、严格 UTF-8 |
| PDF | 沿用原有类型；需要打开密码时返回 422 `DOCUMENT_PASSWORD_REQUIRED` |

错误码：415 `UNSUPPORTED_DOCUMENT`（伪装、宏、旧版 .doc/.xls、非 UTF-8）；422 `DOCUMENT_CORRUPTED`（结构损坏、压缩炸弹、路径穿越）、`DOCUMENT_PASSWORD_REQUIRED`（加密的 docx/xlsx/PDF）、`DOCUMENT_CONVERSION_FAILED`、`DOCUMENT_CONVERSION_TIMEOUT`（超过 120 秒）；503 `CONVERTER_UNAVAILABLE`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/matrix` | `{routes[{from, to, engine, available, taskType}], notSupported[]}`：本机当前真实可用的转换路线 |
| POST | `/convert` | `{sourceFileId, targetFormat: "pdf", options}` + `Idempotency-Key` → 202 `{taskId, status, statusUrl}`（任务类型 `document.convert`） |
| GET | `/{fileId}/inspect` | 结构信息：docx `{paragraphs, tables[], pageSize, fonts[], missingFonts[]}`；xlsx `{sheets[{name, hidden, usedRows, usedCols, mergedRegions, printArea?, landscape}], fonts[], missingFonts[]}`；html `{title, willRemove{…}}`；pdf `{pageCount, pageSize, fonts[]}`；均含 `warnings[]` |
| GET | `/{fileId}/preview?page=1` | 仅 PDF：`{page, pageCount, pageSize, imageTicketUrl, expiresAt}`，页码越界返回 400 |
| GET | `/{fileId}/preview/{page}.png?ticket=` | 页面 PNG；票据只对该文件有效，10 分钟过期 |

`options`（不接受其它键）：

- HTML：`pageSize` `A4`（默认）| `ORIGINAL`，`orientation` `PORTRAIT` | `LANDSCAPE`，`marginMm` 0–30（默认 10）。
- XLSX：`sheetNames[]`（默认输出全部未隐藏的工作表；名称不存在返回 400，并在 `details.fields.sheetNames` 中列出可选名称）。
- DOCX / XLSX 沿用文档自身的页面设置，传入纸张、方向或边距会返回 400。

引擎：DOCX/XLSX 使用 LibreOffice headless，HTML 使用本机 Chrome / Edge 的 headless 模式。每个任务使用独立的配置目录，输入文件先复制再处理。HTML 先经过清洗（移除脚本、事件属性、iframe/object、外部样式与任何非 `data:` 的资源链接），再注入 `default-src 'none'` 的 CSP，浏览器启动时把所有域名解析指向无效地址并配置不可达的代理，因此无法访问网络或本地文件。

转换结果 `result`：`{sourceFormat, targetFormat, engine, pages, pageSize, outputSize, warnings[], removed?{…}, sheets?[], verification}`。`verification` 检查 PDF 文件头、非空、页数 ≥ 1、首页和末页可渲染（空白页只记为提示，不判失败）。缺少的字体会写入 `warnings`，不会假装版式没有变化。

## 13. 材料规范官 `/api/v2/specs`（V2）

替代原 V2 文档中的"协作交付（公开表单）"。所有接口都会校验项目归属，访问别人的项目一律返回 404。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/` | `{name}` → 201 项目 |
| GET | `/` | `{items[{id, name, slots, people, files, records, updatedAt}], total}`；records 为有效收集表记录数 |
| GET / DELETE | `/{id}` | 项目详情（`spec`、`roster`、`templates`、`ai`、`namingUnknown?`）；删除项目时文件保留在"我的文件"中，保留期恢复为默认 24 小时 |
| POST | `/{id}/extract` | `{noticeText}` → AI 草稿 `{spec, errors[], warnings[], model, promptVersion, rounds}`，**不保存**。只把通知原文发给模型；出现校验错误时自动修正 1 次 |
| PUT | `/{id}/spec` | `{spec, version, noticeText?}`：校验后保存，`version` 不一致返回 409 `VERSION_CONFLICT`；保存后已放入的材料按新规范重新检查 |
| POST | `/{id}/roster` | `{fileId, headerRow?, nameField?}`：导入 .xlsx 名单（自动识别表头、姓名列和学号/编号列） |
| PUT | `/{id}/templates` | `{docxFileId?, xlsxFileId?, xlsxHeaderRow?}` |
| POST | `/{id}/items` | `{files[{fileId, relativePath}]}`（≤ 500 个）：放入材料、自动匹配并检查，返回看板 |
| GET | `/{id}/items` | 看板 `{people[{key, name, complete, cells{slotKey: {status, itemId?, count}}}], summary, items[]}`；读取时会同步已结束的修正任务结果 |
| PATCH / DELETE | `/{id}/items/{itemId}` | 手动指定 `{personKey, slotKey}`；移出文件 |
| POST | `/{id}/fix` | `{itemIds?}`：为可修正的文件提交修正任务（复用 `video.compress` / `audio.extract` / `image.process` / `document.convert`），返回看板与 `started` |
| POST | `/{id}/deliveries` | `{onlyComplete, includeOriginals}` + `Idempotency-Key` → 202 任务（`spec.deliver`）；同一项目已有交付在运行时返回 409 `DELIVERY_RUNNING` |
| GET | `/{id}/deliveries` | 该项目最近的交付任务（TaskView） |
| GET | `/{id}/collections` | 负责人查看收集链接状态、累计文件数和容量；不返回令牌 |
| POST | `/{id}/collections` | `{days: 1..30}` → 链接状态及 `path`（令牌仅本次返回）；旧链接立即停用 |
| DELETE | `/{id}/collections/{linkId}` | 停止收集，204 |
| POST | `/{id}/organize` | AI 补全最多 100 个未匹配文件，返回看板及 `organization{matched,pending,model}`；只填缺失项，保留人工分配；项目并发变化返回 409 |

**规范**（`spec`）：`{title, sizeUnit: DECIMAL|BINARY, slots[{key, label, kind: VIDEO|AUDIO|IMAGE|DOCUMENT, required, rules, evidence?, evidenceVerified?}], naming{pattern, evidence?}, notes[]}`。各类型可用的规则：视频为 `extensions/maxBytes/maxWidth/maxHeight/min|maxDurationSec`，音频为 `extensions/maxBytes/min|maxDurationSec`，图片为 `extensions/maxBytes/minWidth/minHeight`，文档为 `extensions/maxBytes`；其它键会被拒绝。`maxBytes` 一律是整数字节。规则有 `evidence` 时，服务端会逐字（忽略空白）核对这句话是否出现在通知原文中，找不到就把 `evidenceVerified` 设为 `false` 并给出警告。命名规则可以用名单的列名，以及 `{序号}`、`{材料}`、`{原文件名}`；"材料名称"等说法会自动归一为 `{材料}`。

**匹配**（确定性规则，不调用模型）：优先按学号/编号（≥ 4 位）匹配；其次按姓名，多个姓名同时命中时取最长的那个，且必须唯一；名单中有重名，或匹配不唯一时，留给用户手动指定。材料项按文件类型匹配，同类型有多个材料项时再按名称区分。

**分享收集**（匿名）：`GET /api/public/collections/{token}` 返回项目名称、公开材料要求、到期时间、单文件上限；设置表格字段后额外返回 `form` 配置及 `formKey`（个人凭证的存储命名空间）。无表格字段时，`POST /api/public/collections/{token}/submissions` 为原有文件收集 multipart：`file`、`name`、`identifier?`、`slotKey`、`requestKey`（16-80 位字母/数字/下划线/短横线）。有表格字段时须先创建记录，再使用下述行级附件接口。提交成功仅返回 `{receipt, filename, submittedAt}`，不返回内部文件 ID、匹配结果或完整名单。同链接同 `requestKey` 重试返回原回执，且表格附件会核对原记录，不能跨记录复用回执。无效、停用、到期令牌统一 404。单文件 200MiB；链接累计 500 文件 / 2GiB，额度耗尽或匿名操作过频返回 429。负责人材料看板含 `submittedBy`、`submittedAt`；`matchMethod` 包含 `SUBMISSION` 和 `AI`。

### 在线协作收集表

负责人前缀为 `/api/v2/specs/{id}/form`，分享前缀为 `/api/public/collections/{token}/form`。两者共用数据和业务规则，负责人路由必须为项目所有者；分享路由始终先验证当前链接，再校验 JWT / `X-Participant-Token` 和行级权限。所有响应禁止缓存。

| 方法 | 相对路径 | 说明 |
|---|---|---|
| GET / PUT | `/` | GET 返回配置及 version；PUT `{config,version}` 仅负责人设置字段与权限，版本冲突 409 |
| POST | `/participants` | `{name,secret}`：登记或恢复身份；secret 为 43 位 base64url 随机凭证。登录账号以 JWT 为准，可绑定本人持有的匿名凭证；绑定后必须登录 |
| GET / PUT | `/members` | 仅负责人列出 / 设置成员；PUT `{username,role:EDITOR|VIEWER|null}`，null 为撤销 |
| GET | `/rows?page=0&size=50&deleted=false` | 返回 `{config,actor,permissions,rows,total,page,size}`；size 最大 100，回收站仅负责人可读 |
| POST | `/rows` | `{values,requestKey,configVersion}`：同身份同 requestKey 幂等，默认每人一条，可设置多条 |
| PATCH | `/rows/{rowId}` | `{values,version,configVersion}`：values 为字段补丁；记录或表格版本不符 409，禁止静默覆盖 |
| DELETE | `/rows/{rowId}?version=N` | 仅本人且具备编辑权，或负责人，可移入回收站；协作编辑者不能删除别人的记录 |
| POST | `/rows/{rowId}/restore?version=N` | 仅负责人恢复，保留数据和附件 |
| GET | `/statistics` | 仅统计当前可见记录：人数、记录数、附件数、各字段完整度、不同/重复值、前 100 分组；数字附精确十进制 sum/average/min/max |
| GET | `/export` | 下载当前可见记录的 XLSX；全部单元格显式文本，保留小数精度且不执行公式 |
| GET | `/rows/{rowId}/discussion` | 当前可见记录的留言（最多 200 条）与最近 50 条修改记录 |
| POST | `/rows/{rowId}/comments` | `{content}`，最多 1000 字；查看范围与记录一致，关闭留言或停止收集后拒绝普通成员写入 |
| POST | `/rows/{rowId}/files` | multipart `file,slotKey,requestKey`；按行级附件权限校验，再进入类型检测与材料检查流程 |
| GET | `/rows/{rowId}/files/{itemId}` | 动态校验当前行可见权限后返回附件；不向客户端暴露原库 fileId 或下载票据 |

配置为 `{viewMode:OWN|ALL, editMode:OWN|ALL|DESIGNATED|LOCKED, accepting, allowMultiple, allowComments, fields:[{key,label,type,required,options?}]}`；支持 TEXT/LONG_TEXT/NUMBER/SELECT/DATE/CHECKBOX，最多 20 字段。所有人编辑须允许全表查看。指定 EDITOR/VIEWER 可看全表，VIEWER 不可写；LOCKED 模式下指定 EDITOR 也不能修改。accepting=false 后普通参与者与指定成员均不能修改，负责人仍可管理。成员移除或模式变更后，下次请求重新判断权限，统计、导出、附件和留言不例外。

已存在记录的字段不可删除或改类型；新增必填字段不删除旧记录，但后续修改须补全。每项目累计最多 2000 条记录（含回收站）、5000 位登记参与者；行值 JSON 最大 16000 字符。文本有长度限制，数字最多 12 位整数、6 位小数和 15 位有效数字。匿名凭证只存摘要，不能凭姓名查询他人记录；收集表字段值不会发送给 AI。

**AI 整理**：仅发送文件名/路径、提交姓名、名单姓名及材料名称/类型；不发送文件内容和名单其他列。模型仅能提出已有项目中的 item/person/slot key，所有结果先整体校验，低于 0.9 置信度的建议不应用，材料类型不兼容或存在未知 key 时整次拒绝并保持原状态。模型请求期间不持有数据库写锁；应用前核对规范、名单及材料快照，防止覆盖并发修改。

**材料状态**：`UNMATCHED`（未匹配）、`OK`（合格）、`FIXABLE`（可自动修正）、`INVALID`（不合格且无法修正）、`FIXING`（修正中）、`FIXED`（已修正，修正后的文件按同一套规则重新检查通过）、`FIX_FAILED`（修正失败）。

**交付包**：`00_交付说明.txt`、`01_汇总表/汇总.xlsx`（填写 XLSX 模板或自动生成）、`02_个人表/<序号>_<姓名>.docx`（DOCX 模板中的 `{{字段}}` / `${字段}` 占位符，即使被 Word 拆进多个 run 也能替换）、`03_材料/<序号>_<姓名>/<命名规则>.<扩展名>`（可选附带 `原始素材/`）、`90_校验报告/manifest.csv · exceptions.csv · verification.json`、`sha256sums.txt`。生成时读取不可变快照。XLSX 写入区域已有内容或公式时拒绝覆盖，并报出具体单元格坐标；所有值按文本写入，CSV 中以 `= + - @` 开头的单元格会加前缀 `'`。打包前会回读核验：汇总表行数、个人表中有数据的占位符是否全部替换、校验和。存在缺交或不合格项时 `result.overall` 为 `PARTIAL_SUCCESS`。

## 14. 学习播放器 `/api/v2/lessons`（V2）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/capabilities` | `{asr{configured, provider, model}, ocr, llm, maxDurationMin}`，只返回是否就绪和模型名，不返回任何 Key |
| POST | `/` | `{videoFileId}` + `Idempotency-Key` → 202 任务（`lesson.analyze`）；语音识别或 DeepSeek 未配置时返回 503，不会创建任务 |
| GET | `/` | `{items[{id, title, videoFileId, duration, nodes, snippets, createdAt}]}` |
| GET | `/{id}` | `{lesson{title, summary, duration, nodes[], snippets[]}, transcript[{begin, end, text}], screen[{t, text}], meta{asrModel, llmModel, promptVersion, frames, ocrFrames, warnings[]}}` |
| POST | `/{id}/ticket` | `{frameUrl}`：证据帧的图片地址模板（`{frame}` 替换为帧文件名），10 分钟有效，只对这节课有效 |
| GET | `/{id}/frames/{frame}?ticket=` | 证据帧 JPEG |
| DELETE | `/{id}` | 删除分析结果和证据帧；原视频保留 |

**处理流程**（任务阶段名即 `stage`）：提取音轨（16 kHz 单声道 MP3）→ 语音识别（阿里云百炼 Paraformer，录音文件识别：先通过 `getPolicy` 上传到百炼的临时存储得到 `oss://` 地址，再提交异步任务并轮询，结果为毫秒级的句子时间戳）→ 抽取关键帧（默认每 3 秒 1 帧，最多 1200 帧）→ 画面文字识别（本机 RapidOCR，相邻帧画面相同时复用上一帧的结果）→ 整理知识大纲（DeepSeek，模型只能通过一个工具提交结果，校验失败时自动修正 1 次）→ 保存。

**校验**：每个节点的时间都必须在视频时长内，且 `end > start`；节点必须构成合法的树（父节点存在、无环、最多 3 层）；子节点超出父节点的时间范围时会被收拢进去，并给出提示。每个代码片段会和时间范围前后 30 秒内的画面文字逐行比对：`ocrSupport` 是画面上能找到的代码行占比；`source` 为 `SCREEN`（≥ 0.7，画面识别）、`SCREEN_COMPLETED`（0.3–0.7，画面识别 + AI 补全）或 `MODEL`（< 0.3，AI 根据讲解整理，同时给出警告）；`evidenceFrame` 是匹配行数最多的那一帧。画面文字的缩进按 OCR 文字框的左边界、以字符宽度为单位还原。

**沙盒**（浏览器端）：Python 使用本站提供的 Pyodide（`/pyodide/`），JavaScript 在独立的 Web Worker 中运行。用户代码执行前会移除 `fetch`、`XMLHttpRequest`、`WebSocket`、`importScripts` 等能力，Worker 本身也没有 DOM；超时（JS 3 秒、Python 10 秒）后直接终止 Worker；输出超过 20,000 字符会被截断。

## 15. 智能模式 `/api/ai`（新增）

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

### V2 扩展（prompt 1.5.0）

- 新增只读工具 `inspect_document`（文档结构、字体、页面）和 `get_conversion_matrix`（本机真实可用的转换路线）；计划可以使用 `document.convert`。
- 禁止进入 AI 计划的操作：`spec.deliver`（生成交付包）、`spec.delete`、`file.delete`、`flow.publish`、`flow.delete`。计划校验器会拒绝这些操作，并把原因反馈给规划官。
- `submit_requirements.constraints`：用户的硬性要求 `{video{container, maxSizeMB, maxHeight, keepAudio}, audio{format}, image{format, maxSizeKB}, document{format}}`。服务端只保留已知且在范围内的字段，体积换算为整数字节（视频按 MiB、图片按 KiB）。约束会写入 `plan.constraints` 与 `plan.constraintsText`，在用户确认计划时展示。
- 审核时，质量审核官会把每条约束对照**最终交付的文件**（没有被后续步骤继续处理的输出）逐条检查，检查项名称以 `约束·` 开头；某类约束没有对应的交付文件也算不通过。`review.verdict` 为 `VERIFIED` 当且仅当所有检查都通过，否则为 `FAILED`。
- `document.convert` 的结果会检查 PDF 文件头、页数和首页渲染。

计划步骤里的 `tool` 与 `/api/tasks` 的任务类型一一对应，`params` 与专业模式的参数相同（见第 4–8 节）；`inputs` 中的 `"step:N"` 表示使用第 N 步的输出文件。
