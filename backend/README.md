# Video Compressor Backend 多媒体处理服务

基于 Spring Boot 的多媒体处理后端，为前端提供视频、音频、图片、PDF 以及文件元数据的统一处理能力。服务整合 FFmpeg、ImageMagick、Ghostscript 与基于 Python 的 rembg 模型，所有耗时任务均异步运行并提供实时进度查询。

## 目录
- [功能概览](#功能概览)
- [架构与模块](#架构与模块)
- [外部依赖](#外部依赖)
- [配置说明](#配置说明)
- [构建与运行](#构建与运行)
- [任务与文件流转](#任务与文件流转)
- [API 参考](#api-参考)
  - [通用约定](#通用约定)
  - [视频接口 `/api/video`](#视频接口-apivideo)
  - [音频接口 `/api/audio`](#音频接口-apiaudio)
  - [图片接口 `/api/image`](#图片接口-apiimage)
  - [PDF 接口 `/api/pdf`](#pdf-接口-apipdf)
  - [元数据接口 `/api/metadata`](#元数据接口-apimetadata)
- [常见问题](#常见问题)

## 功能概览
- **视频工具箱**：上传分析、CRF / 指定码率压缩、格式转换、图片水印叠加、处理后文件下载。
- **音频工具箱**：从视频中提取音轨，输出 MP3 / WAV / FLAC / AAC。
- **图片工具箱**：批量压缩、改尺寸、格式转换、SVG 高清渲染、AI 去背景。
- **PDF 工具箱**：PDF 批量渲染为图片、多张图片合并为单一 PDF。
- **元数据助手**：读取/清除/修改图片及音视频文件的 EXIF、GPS、HDR 等关键元数据。
- **任务进度**：所有长耗时任务都会生成唯一 `taskId`，可通过统一接口轮询进度、状态与下载文件名。

## 架构与模块
```
com.houqing.tools.video_compressor_backend
├── config          # 全局配置 (CORS)
├── controller      # 各功能域的 REST 控制器
├── dto             # 请求/响应数据模型
├── service         # 业务逻辑，封装 FFmpeg / ImageMagick / rembg 调用
└── ...
```
- `VideoService` / `AudioService` / `ImageService` / `PdfService` / `MetadataService`：封装对应领域的处理逻辑。
- `TaskService`：使用 `ConcurrentHashMap` 管理任务状态，提供 `PENDING/PROCESSING/COMPLETED/FAILED` 生命周期。
- `@EnableAsync`：异步执行耗时任务，避免阻塞 HTTP 请求线程。

## 外部依赖
除 JDK 17+ 与 Maven 外，需要下列命令行工具。请确保安装后可在系统 `PATH` 中访问，或在 `application.properties` 中明确指定路径。

| 依赖 | 用途 | 必需 | 验证命令 |
| ---- | ---- | ---- | ---- |
| [FFmpeg](https://www.gyan.dev/ffmpeg/builds/) / FFprobe | 视频压缩、转码、音频提取、水印 | ✅ | `ffmpeg -version` / `ffprobe -version` |
| [ImageMagick (`magick`)](https://imagemagick.org/script/download.php) | 图片批处理、PDF ↔ 图片互转 | ✅ | `magick -version` |
| [Ghostscript](https://www.ghostscript.com/releases/gsdnld.html) | PDF 渲染（被 ImageMagick 调用） | ✅ | `gswin64c -v` (Windows) |
| Python 3.x | AI 去背景运行时 | ✅ | `python --version` |
| [rembg CLI](https://github.com/danielgatis/rembg) | AI 抠图模型 | ✅ | `rembg --version`（或在 Conda 中执行） |

> 建议：将 FFmpeg、ImageMagick 安装在无空格、无中文路径；使用 Conda 独立环境运行 rembg 以隔离依赖。

## 配置说明
关键配置位于 `src/main/resources/application.properties`：

```properties
# 临时上传目录（确保存在且可写）
file.upload-dir=C:/TestFile/uploads/
# 处理结果输出目录
file.output-dir=C:/TestFile/outputs/

# FFmpeg 路径（也可依赖 PATH）
ffmpeg.ffprobe-path=D:/ffmpeg/bin/ffprobe.exe
ffmpeg.ffmpeg-path=D:/ffmpeg/bin/ffmpeg.exe

# ImageMagick 与 AI 去背景
imagemagick.magick-path=magick
ai.conda-path=D:/anaconda/condabin/conda.bat
ai.rembg-env-name=rembg-env
# 如使用全局 rembg，可改为：ai.rembg-path=rembg

# 上传限制
spring.servlet.multipart.max-file-size=5000MB
spring.servlet.multipart.max-request-size=5000MB
```

跨域配置位于 `config/CorsConfig.java`，默认允许 `http://127.0.0.1:5173` 与 `http://localhost:5173`，可按需调整。

## 构建与运行
```bash
# 构建
./mvnw clean package

# 运行开发环境
./mvnw spring-boot:run

# 运行测试
./mvnw test
```
服务器默认监听 `8080` 端口，主类 `VideoCompressorBackendApplication`。

## 任务与文件流转
1. 客户端先调用上传接口获取 `uniqueFilename`（视频/图片/元数据文件）。
2. 提交处理任务时传入 `uniqueFilename` 及处理参数，服务端立即返回 `taskId`。
3. 前端轮询 `/progress/{taskId}` 获取 `status`、`progress`、`message` 与 `downloadFilename`。
4. 当任务 `COMPLETED` 时，使用下载接口获取输出文件；若 `FAILED`，查看 `message` 了解错误原因。

上传文件保存在 `file.upload-dir`，输出写入 `file.output-dir`。任务完成后不会自动清理原始上传文件，请根据业务需要定期维护。

## API 参考

### 通用约定
- 请求与响应均为 UTF-8 编码。
- 上传接口使用 `multipart/form-data`；其他处理接口为 `application/json`。
- 所有耗时操作均返回 `{ "taskId": "..." }`，使用对应的 `/progress/{taskId}` 查询任务进度。
- `TaskProgressDTO` 字段：
  ```json
  {
    "taskId": "c5d...",
    "status": "PENDING|PROCESSING|COMPLETED|FAILED",
    "progress": 0,
    "message": "任务已提交，等待处理...",
    "downloadFilename": "optional"
  }
  ```
- 失败时返回 `400 Bad Request`（参数问题）或 `500 Internal Server Error`，响应体包含错误描述。

### 视频接口 `/api/video`
| 方法 | 路径 | 描述 |
| ---- | ---- | ---- |
| `POST` | `/upload` | 上传并分析视频，返回基础元数据与 `uniqueFilename`。 |
| `POST` | `/compress` | 提交压缩任务。支持 `crf` 或 `bitrate` 模式。 |
| `POST` | `/convert` | 提交格式转换任务，按目标扩展名输出。 |
| `POST` | `/watermark` | 为视频叠加图片水印，支持九宫格+中心等位置及透明度。 |
| `GET` | `/progress/{taskId}` | 查询视频任务进度。 |
| `GET` | `/download/{filename}` | 下载处理后的文件。 |

**`POST /api/video/upload`**
- 请求：`file` (MultipartFile)
- 响应：
  ```json
  {
    "videoInfo": {
      "originalFilename": "demo.mp4",
      "formatName": "mov,mp4,m4a,3gp,3g2,mj2",
      "duration": 61.24,
      "size": 104857600,
      "width": 1920,
      "height": 1080,
      "videoBitrate": 4200000,
      "audioBitrate": 192000
    },
    "uniqueFilename": "uuid_demo.mp4"
  }
  ```

**`POST /api/video/compress`**
- 请求体：
  ```json
  {
    "uniqueFilename": "uuid_demo.mp4",
    "mode": "crf",
    "crfValue": 23,
    "audioBitrate": 192000,
    "duration": 61.24,
    "width": 1920,
    "height": 1080
  }
  ```
  - `mode=crf`：使用恒定质量模式，`crfValue` 范围 `0-51`（越小质量越高）。
  - `mode=bitrate`：需提供 `targetSizeMB`、`audioBitrate`、`duration` 用于重新估算视频码率。
- 响应：`{ "taskId": "..." }`
- 输出文件：`compressed_{uniqueFilename}`

**`POST /api/video/convert`**
- 请求体：`{ "uniqueFilename": "uuid_demo.mp4", "targetFormat": "mov", "duration": 61.24 }`
- 输出文件：`converted_uuid_demo.mov`

**`POST /api/video/watermark`**
- 请求体：
  ```json
  {
    "videoUniqueFilename": "uuid_video.mp4",
    "watermarkUniqueFilename": "uuid_logo.png",
    "duration": 61.24,
    "position": "bottom-right",
    "opacity": 0.65,
    "margin": 24
  }
  ```
  - 位置可选：`top-left` / `top-right` / `bottom-left` / `bottom-right`(默认) / `center` / `top-center` / `bottom-center` / `center-left` / `center-right`
  - `opacity` 范围 0~1。
- 输出文件：`watermarked_{videoUniqueFilename}`

**`GET /api/video/progress/{taskId}`**
- 响应：`TaskProgressDTO`

**`GET /api/video/download/{filename}`**
- 直接下载输出目录中的文件。

### 音频接口 `/api/audio`
| 方法 | 路径 | 描述 |
| ---- | ---- | ---- |
| `POST` | `/extract` | 提取音频轨道并转码。 |
| `GET` | `/progress/{taskId}` | 查询音频任务进度。 |

**`POST /api/audio/extract`**
- 请求体：
  ```json
  {
    "uniqueFilename": "uuid_demo.mp4",
    "duration": 61.24,
    "targetFormat": "mp3"
  }
  ```
  - 支持 `mp3`（`libmp3lame` 可变码率）、`wav`、`flac`、`aac`（默认复制编码）。
- 输出文件：`audio_uuid_demo.mp3`

### 图片接口 `/api/image`
| 方法 | 路径 | 描述 |
| ---- | ---- | ---- |
| `POST` | `/upload` | 上传图片文件，返回 `uniqueFilename`。 |
| `POST` | `/process` | 批量处理图片（压缩/改尺寸/格式转换/SVG 渲染）。 |
| `POST` | `/remove-background` | 调用 rembg 去除背景。 |
| `GET` | `/progress/{taskId}` | 查询图片任务进度。 |

**`POST /api/image/upload`**
- 请求：`file` (MultipartFile)
- 响应：`{ "uniqueFilename": "uuid_demo.png" }`

**`POST /api/image/process`**
- 请求体：
  ```json
  {
    "filenames": ["uuid_a.png", "uuid_b.jpg"],
    "tool": "resize",
    "settings": { "width": 800, "height": 600 }
  }
  ```
  - `tool` 可选：
    | Tool | 说明 | `settings` 示例 |
    | ---- | ---- | ---- |
    | `convert` | 转换格式（含 SVG→PNG 双阶段渲染） | `{ "targetFormat": "png" }` |
    | `resize` | 修改尺寸，可保持比例 | `{ "width": 800, "height": 600 }`（可只填其一） |
    | `compress` | 控制输出质量 | `{ "quality": 85 }` |
  - 结果会打包为 `processed_{taskId}.zip`，包含处理后的图像。

**`POST /api/image/remove-background`**
- 请求体：`{ "uniqueFilename": "uuid_person.png" }`
- 输出文件：`{originalName}_bg_removed.png`
- 依赖 `conda run -n {ai.rembg-env-name} rembg`，首次执行会下载模型，耗时较长。

### PDF 接口 `/api/pdf`
| 方法 | 路径 | 描述 |
| ---- | ---- | ---- |
| `POST` | `/process` | PDF ↔ 图片互转，具体行为由 `tool` 决定。 |
| `GET` | `/progress/{taskId}` | 查询 PDF 任务进度。 |

**`POST /api/pdf/process`**
- `tool = "pdf-to-image"`
  ```json
  {
    "filenames": ["uuid_doc.pdf"],
    "tool": "pdf-to-image",
    "settings": {
      "density": 200,
      "format": "png",
      "quality": 90
    }
  }
  ```
  - 输出：`pdf_to_images_{taskId}.zip`，包含按页命名的图片。
- `tool = "images-to-pdf"`
  ```json
  {
    "filenames": ["uuid_page1.png", "uuid_page2.png"],
    "tool": "images-to-pdf",
    "settings": {}
  }
  ```
  - 输出：`images_to_pdf_{taskId}.pdf`

### 元数据接口 `/api/metadata`
| 方法 | 路径 | 描述 |
| ---- | ---- | ---- |
| `POST` | `/upload` | 上传任意媒体文件用于元数据操作。 |
| `POST` | `/view` | 查看并翻译元数据（含 HDR、色彩空间、GPS）。 |
| `POST` | `/remove` | 清理所有元数据，输出无敏感信息版本。 |
| `POST` | `/modify` | 修改部分 EXIF 信息（设备、时间、GPS）。 |

**`POST /api/metadata/upload`**
- 请求：`file`
- 响应：`{ "uniqueFilename": "uuid_photo.jpg" }`

**`POST /api/metadata/view`**
- 请求体：`{ "uniqueFilename": "uuid_photo.jpg" }`
- 响应：结构化 JSON，已翻译为易读字段：文件信息、视频/音频流详情、HDR & 色彩空间、GPS（自动生成高德地图链接）等。

**`POST /api/metadata/remove`**
- 请求体：`{ "uniqueFilename": "uuid_photo.jpg" }`
- 响应：`{ "downloadFilename": "uuid_photo_clean.jpg" }`

**`POST /api/metadata/modify`**
- 请求体：
  ```json
  {
    "uniqueFilename": "uuid_photo.jpg",
    "model": "iPhone 16 Pro",
    "dateTimeOriginal": "2024:04:06 18:30:00",
    "gpsLatitude": 31.2304,
    "gpsLongitude": 121.4737
  }
  ```
- 响应：`{ "downloadFilename": "uuid_photo_modified.jpg" }`

## 常见问题
- **进度一直 0%？**
  - 检查外部命令是否可执行（权限/路径）。
  - 确认 `duration`、`audioBitrate` 等参数是否正确，以便准确计算进度。
- **PDF 转图片失败**：确保 Ghostscript 已安装并被 ImageMagick 识别。
- **去背景报错**：确认 `ai.conda-path`、`ai.rembg-env-name` 正确；首次运行需等待模型下载。
- **下载文件 404**：任务尚未 `COMPLETED` 或 `downloadFilename` 为空，请确认任务状态。

如需扩展，请在相应 Service 中添加业务逻辑，并通过 `TaskService` 返回统一的任务进度。
