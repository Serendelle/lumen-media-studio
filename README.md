# Lumen · 流明智媒

> Every frame, in its best light.

由 **Serendelle 个人独立开发**。

面向创作者的媒体处理工作台，集中处理视频、音频、图片、文档和隐私信息。专业模式提供具体参数；智能模式根据自然语言生成计划，经你确认后执行，再核验处理结果。

![Lumen 当前首页：黑色点阵背景与 Every frame, in its best light. 标题](assets/screenshots/01-landing-current.png)

默认首页使用黑色点阵与声呐动效。点击 **Start working / 进入工作台** 后，通过侧边栏访问各个模块；首次使用需要注册或登录。旧版浅色首页保留在 `/classic`。

## 能力

| 领域 | 能力 | 引擎 |
|---|---|---|
| 视频 | 按目标体积压缩（两遍编码，提交前评估可行性）/ CRF / 指定码率；H.264 / H.265；分辨率、帧率、音频参数；格式转换（MP4/MOV/WebM/MKV/GIF）；图片水印 | FFmpeg |
| 音频 | 从视频中提取 MP3 / AAC / M4A / WAV / FLAC，可设置码率、采样率、声道 | FFmpeg |
| 图片 | 批量压缩（JPG/WebP/AVIF 可按目标体积压缩）、改尺寸、转格式；处理前后对比；AI 抠图 | ImageMagick · rembg |
| 文档 | 多张图片合并为 PDF；PDF 转图片 | ImageMagick · Ghostscript |
| 隐私 | 查看元数据、编辑标题/作者/拍摄时间/GPS（按格式的真实能力开放字段）；一键清除位置信息或全部元数据，并独立核验 | ExifTool · FFmpeg |
| 文档工作台 | Word / Excel / 网页转 PDF，上传前校验文档结构，转换后核验 PDF | LibreOffice · Chromium · PDFBox |
| 材料规范官 | 在线收集表、自定义字段；按个人/全员/指定成员协作；留言、附件、回收站；一键统计与 Excel 导出；AI 匹配材料并生成交付包 | DeepSeek · Apache POI · 上述引擎 |
| 学习播放器 | 教学视频 → 随播放生长的知识图谱；讲到代码时，代码出现在浏览器沙盒中，可以直接运行 | 阿里云百炼 · RapidOCR · DeepSeek · Pyodide · Monaco |
| 智能模式 | 需求理解 → 任务规划 → **用户确认** → 执行 → 质量审核；过程实时可见，可断线续传 | DeepSeek |

## 快速开始（Windows 10 / 11）

先准备 **JDK 17+、Node.js 22.12+、FFmpeg / FFprobe 和 ExifTool**。Node.js 也支持 20.19+ 的 20.x 版本。图片、PDF、Office 转换等功能还需要相应工具，安装方式见 [部署指南](docs/deployment.md)。

```powershell
git clone https://github.com/Serendelle/lumen-media-studio.git
cd lumen-media-studio

# 首次创建本机配置；保留已经存在的 .env
if (-not (Test-Path .env)) { Copy-Item .env.example .env }

# 检查依赖，按提示补齐后启动
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-environment.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-windows.ps1
```

启动脚本会按需安装前端 npm 依赖、下载 Maven 依赖并构建前后端，然后由同一个后端进程提供页面和 API。媒体处理工具需要预先安装；缺少可选工具时，对应功能会显示不可用。

- 项目页面：<http://localhost:8080>
- API 文档：<http://localhost:8080/swagger-ui.html>
- 健康检查：<http://localhost:8080/actuator/health>

```powershell
# 修改代码后重新构建并启动：先停止现有服务，再使用 -Rebuild
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-windows.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\start-windows.ps1 -Rebuild

# 停止服务
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\stop-windows.ps1
```

启动参数还支持 `-NoBrowser`（不打开浏览器）和 `-Port 8090`（指定端口）。

> **关于密钥**：DeepSeek API Key 只保存在本机的 `.env` 文件中（已被 `.gitignore` 排除），只由后端读取，**不会**出现在前端、日志或仓库里。仓库只提供带占位符的 [`.env.example`](.env.example)。

## AI 与外部工具配置

在仓库根目录的 `.env` 中填写自己的配置，参考 [`.env.example`](.env.example)：

```dotenv
DEEPSEEK_API_KEY=sk-your-key-here
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-flash

# 学习播放器的语音识别
DASHSCOPE_API_KEY=sk-your-key-here
```

- 未配置 DeepSeek Key 时，可使用已安装处理工具支持的专业模式；智能模式及其他依赖 DeepSeek 的功能需要配置 Key。
- 学习播放器还需要百炼 Key 和安装了 RapidOCR 的 Python 环境；可用 `OCR_PYTHON` 指定其 `python.exe`。
- AI 抠图需要 rembg，首次使用会下载模型，默认使用 `u2net`。
- 工具不在 PATH 中时，可在 `.env` 中设置 `FFMPEG_PATH`、`EXIFTOOL_PATH`、`MAGICK_PATH`、`GHOSTSCRIPT_PATH`、`SOFFICE_PATH` 等完整路径。环境检查与后端会读取这些配置，便携版 Ghostscript 也受支持。
- 修改 `.env` 后需要重启服务。真实模型请求按服务商规则计费。

## 页面入口

以下路径均位于本地服务 `http://localhost:8080` 下，工作台页面需要登录。

| 页面 | 路径 |
|---|---|
| 品牌首页 | `/` |
| 工作台概览 | `/workspace` |
| 媒体工作台 · 专业模式 | `/workspace/media` |
| 智能模式 | `/workspace/ai` |
| 文档工作台 | `/workspace/documents` |
| 材料规范官 | `/workspace/specs` |
| 材料收集页（免登录） | `/collect/<随机令牌>` |
| 学习播放器 | `/workspace/learn` |
| 我的文件 | `/workspace/files` |
| 任务中心 | `/workspace/tasks` |

## 多人收集与 AI 整理

1. 在材料规范官新建项目，在 **收集与权限** 中设置字段：短文本、长文本、数字、单选、日期、勾选，最多 20 个字段。仅收集表格信息时不需要名单或材料规范；需要附件时，在 **规范** 中设置材料项和格式要求。
2. 选择查看、编辑范围并保存，随后生成收集链接（1、7、14 或 30 天）。参与者可以免登录填写；**指定成员按已注册的登录账号授权**，提供全表编辑或全表只读两种角色。
3. 在 **协作表格** 中填写、编辑、留言和补交附件。每 5 秒自动同步最新记录；同时修改发生冲突时保留草稿，独立字段可合并，同一字段冲突需逐项选择，避免覆盖别人。移除记录使用回收站，负责人可以恢复。
4. 点击 **一键统计**，查看记录数、提交人数、附件数、字段填报完整度、不同值/重复值、分组计数，以及数字字段的合计、平均、最小和最大值。统计与 **导出 Excel** 始终遵守当前查看权限；私有模式下普通参与者只能统计、导出自己的记录。数字合计使用精确十进制运算，导出单元格写入文本，避免公式注入。
5. 附件接入原有材料检查流程，在 **材料与 AI 整理** 中查看缺交、重复、不合格和可修正项。**AI 一键整理**仅参考文件名、提交姓名和材料名称，不发送文件内容、收集表字段值和名单其他列；单次最多处理 100 个待匹配文件。在 **交付** 中生成材料包与校验报告。

| 模式 | 普通参与者可见范围 | 编辑范围 |
|---|---|---|
| 每人仅看自己（默认） | 自己的记录、留言和附件 | 各自修改自己的记录 |
| 所有人协作编辑 | 全部记录、留言和附件 | 所有参与者可修改全表 |
| 指定成员编辑 | 普通参与者仅看自己；指定成员可看全表 | 负责人及指定编辑成员；只读成员不可修改 |
| 提交后仅负责人修改 | 可独立设置看自己或全表 | 仅负责人，指定编辑成员也不可修改 |

负责人始终可查看和管理全部记录；「开放填写与修改」关闭后，参与者只能读取已获授权的记录。默认每人一条，也可开启多条填写。查看范围和编辑范围可以分别调整，所有人编辑时须开放全表查看。指定成员授权撤销后，后续请求按普通参与者权限重新判断。字段已有记录后不能删除或改变类型，以保留历史数据。

免登录参与者使用浏览器保存的随机个人凭证识别，**仅填写相同姓名无法获得他人的记录**；清除浏览器数据会失去匿名记录的访问凭证。登录后记录绑定账号，可在其他设备登录查看；绑定后的凭证不能绕过账号登录。记录中的匿名姓名仍由填写者提供，不代表实名核验。普通参与者不能查看负责人导入的完整名单，也不能访问未关联收集记录的散件。

未设置在线字段时，链接继续使用原有的仅提交文件模式，散件仅负责人可见。单个附件最多 200MiB；每个收集链接累计最多 500 个文件、2GiB，删除文件不退回额度。每个项目最多累计 2000 条表格记录（含回收站），每条记录最多 200 条留言。链接可随时停止，重新生成后旧链接失效；完整链接仅在生成时显示，请及时保存。

多人访问需要所有参与者能访问同一服务器。`localhost` 链接只适用于本机；请从可访问的局域网或 HTTPS 部署地址打开工作台后生成链接。真实 AI 调用使用你配置的服务商接口并按其规则计费。

## 技术栈

- 后端：Spring Boot 3.5 · Java 17 · JPA · Flyway 迁移 · SQLite（本地/演示）/ PostgreSQL 16（生产）· Spring Security（BCrypt + JWT）· SSE
- 前端：Vue 3 主应用与业务页面，React 19 首页和工作台导航，TypeScript、Vite 7、Tailwind CSS 4、Pinia、Vue Router、GSAP、Motion、Lenis
- AI：DeepSeek（OpenAI 兼容接口，工具调用 + 流式输出），四个 Agent，版本化的 Prompt 与 SKILL
- 测试：JUnit 5 · MockMvc · jqwik，包含真实媒体处理与输出校验；真实模型测试需要主动启用

## 文档

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 架构图、统一数据流、多 Agent 状态机、安全边界 |
| [docs/api-contract.md](docs/api-contract.md) | 全部 REST / SSE 接口 |
| [docs/backend-audit.md](docs/backend-audit.md) | 原始后端的安全审计与整改对照 |
| [docs/test-report.md](docs/test-report.md) | 历史执行记录、测试环境与验证范围 |
| [docs/deployment.md](docs/deployment.md) | Windows 本地部署与排错 |
| [docs/demo-guide.md](docs/demo-guide.md) | 面向老板与评委的演示手册 |

## 开发

后端（在一个终端中运行）：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

前端（在另一个终端中运行，开发地址为 `http://localhost:5173`，API 代理到 8080）：

```powershell
cd frontend
npm ci
npm run dev
```

构建和本地测试：

```powershell
# 在 frontend 目录执行
npm run build

# 在 backend 目录执行；真实模型测试默认跳过
.\mvnw.cmd test
```

需要使用真实 DeepSeek 的集成测试时，在 `backend` 目录执行 `./mvnw.cmd -Dtest=AiLiveTests -Dai.live=true test`，该命令会消耗 API 额度。历史测试结果与验证范围见 [测试报告](docs/test-report.md)。

## 目录

```
backend/    Spring Boot 服务（媒体引擎、任务引擎、多 Agent、SKILL 与 Prompt）
frontend/   Vue + React 前端、品牌首页、工作台与浏览器代码沙盒
docs/       架构、接口、审计、测试、部署、演示
scripts/    Windows 环境检查 / 一键启动 / 停止
assets/     截图
data/       本机数据库、上传文件、处理结果、日志与本地工具（不提交）
```

默认日志位于 `data/logs/`。`.env`、`data/`、`node_modules/`、`frontend/dist/` 和 `backend/target/` 均不随源码提交。
