# Windows 本地部署指南

适用于 Windows 10 / 11（64 位）。下文所有命令都已在 Windows 11 上实际执行验证。

## 1. 总流程

```powershell
# 在仓库根目录执行
powershell -ExecutionPolicy Bypass -File scripts\check-environment.ps1   # 看缺什么
# ……按第 2 节安装缺少的依赖……
powershell -ExecutionPolicy Bypass -File scripts\start-windows.ps1       # 启动并打开浏览器
```

`start-windows.ps1` 会依次完成以下工作，**不会修改系统设置、环境变量或已安装的软件**：

1. 检查依赖，缺少必需项时停止并给出安装命令
2. 第一次运行时，根据 `.env.example` 生成 `.env`，并写入随机的 `APP_JWT_SECRET`（不显示任何密钥值）
3. 按需构建前端（`npm ci && npm run build`）和后端（`mvnw.cmd package`，Maven Wrapper 会自动下载 Maven，无需另外安装）
4. 在后台启动后端（同时提供前端页面），等待健康检查结果后显示各工具状态，并打开 http://localhost:8080

参数：`-Rebuild`（强制重新构建）、`-NoBrowser`（不自动打开浏览器）、`-Port 8090`（换端口）。

停止服务：`powershell -ExecutionPolicy Bypass -File scripts\stop-windows.ps1`（只结束本项目启动的进程，以及正在执行的 FFmpeg 任务）。

## 2. 依赖

| 依赖 | 必需 | 用途 | 安装命令 |
|---|---|---|---|
| JDK 17+ | ✅ | 后端 | `winget install EclipseAdoptium.Temurin.21.JDK` |
| Node.js 22.12+（或 20.19+ 的 20.x 版本） | ✅（首次构建前端） | 前端构建 | `winget install OpenJS.NodeJS.LTS` |
| FFmpeg / FFprobe | ✅ | 视频、音频 | `winget install Gyan.FFmpeg` |
| ExifTool | ✅ | 元数据与隐私 | `winget install OliverBetz.ExifTool` |
| ImageMagick | 可选 | 图片处理、PDF | `winget install ImageMagick.ImageMagick` |
| Ghostscript | 可选 | PDF | winget 上没有官方包：从 [GitHub ArtifexSoftware/ghostpdl-downloads](https://github.com/ArtifexSoftware/ghostpdl-downloads/releases) 下载 `gs*w64.exe` 安装 |
| LibreOffice | 可选 | 文档工作台：Word / Excel 转 PDF | `winget install TheDocumentFoundation.LibreOffice`；磁盘紧张时可加 `--location D:\LibreOffice`（后端会自动在该目录查找） |
| Chrome 或 Edge | 可选 | 文档工作台：HTML 转 PDF | Windows 自带 Edge 即可；后端优先使用 Chrome |
| Miniconda + rembg | 可选 | AI 抠图 | 见第 4 节 |

安装后**重新打开一个 PowerShell 窗口**，让新的 PATH 生效。后端会自动查找工具：先查 PATH，再查 winget 和常见安装目录，Ghostscript 还会读取注册表。所以一般不需要配置路径；找不到时，可以在 `.env` 里填写 `FFMPEG_PATH=` 等完整路径。

缺少可选依赖时，对应功能在界面上会显示「不可用」，并给出原因，其他功能不受影响。

## 3. 配置 DeepSeek API Key（智能模式）

1. 用记事本打开仓库根目录的 `.env`（第一次运行启动脚本后会自动生成；也可以手动复制 `.env.example`）
2. 把 `DEEPSEEK_API_KEY=sk-your-key-here` 改成你自己的 Key
3. 重新运行 `start-windows.ps1`（如果已在运行，先执行 stop 脚本）

说明：

- `.env` 被 `.gitignore` 排除，**不要**提交、上传或放进安装包。换到其他电脑部署时，请在那台电脑上单独创建 `.env`，不要从公开仓库获取。
- Key 只由后端读取，前端代码、浏览器请求、日志和接口响应中都不会出现（已扫描构建产物和测试日志验证）。
- 如果系统环境变量里已经设置了 `DEEPSEEK_API_KEY`，环境变量的优先级高于 `.env`。
- 没有 Key 时，专业模式完全可用，智能模式会显示「AI 未配置」。
- 可选配置：`DEEPSEEK_MODEL`（默认 `deepseek-flash`）、`DEEPSEEK_BASE_URL`、`DEEPSEEK_PROXY`（只对 DeepSeek 请求生效的出站代理）。

## 4. AI 抠图（可选）

```powershell
winget install Anaconda.Miniconda3
# 新开一个 PowerShell：
& "$env:USERPROFILE\miniconda3\Scripts\conda.exe" create -y -n rembg-env -c conda-forge --override-channels python=3.11
& "$env:USERPROFILE\miniconda3\Scripts\conda.exe" run -n rembg-env python -m pip install "rembg[cpu,cli]"
```

使用 conda-forge 通道，不需要接受 Anaconda 默认通道的服务条款。第一次抠图会下载 u2net 模型（约 170 MB）。后端默认固定使用 `u2net`：rembg 新版的默认模型约 1 GB，可能因内存不足而失败。可以通过 `REMBG_MODEL` 修改。

## 5. 数据与日志

| 位置 | 内容 |
|---|---|
| `data/app.db` | SQLite 数据库（用户、文件、任务、AI 会话） |
| `data/storage/` | 上传的原文件与处理结果（默认保留 24 小时，可通过 `APP_RETENTION_HOURS` 修改） |
| `data/logs/` | `backend.out.log` / `backend.err.log` |

`data/` 已被 `.gitignore` 排除。删除 `data/` 目录即可恢复到全新状态（所有账号和文件都会被清空）。

## 6. 常见问题

| 现象 | 处理 |
|---|---|
| 运行脚本提示「禁止运行脚本」 | 使用文中的 `powershell -ExecutionPolicy Bypass -File …` 写法，只对本次运行生效，不修改系统策略 |
| `端口 8080 已被其他程序占用` | 关闭占用端口的程序，或加 `-Port 8090` |
| `npm ci 失败` | 检查网络；国内网络可设置镜像：`npm config set registry https://registry.npmmirror.com` |
| `后端构建失败` | Maven Wrapper 首次运行需要访问 Maven 中央仓库；检查网络或代理 |
| 启动后某个工具显示 `missing` | 按第 2 节安装，**新开 PowerShell** 后重新运行启动脚本 |
| 智能模式提示「DeepSeek API Key 无效」或「余额不足」 | 检查 `.env` 中的 Key，或在 DeepSeek 控制台检查余额 |
| 智能模式提示「无法连接 AI 服务」 | 后端默认直接连接 `api.deepseek.com`；如果网络必须走代理，在 `.env` 中设置 `DEEPSEEK_PROXY=http://127.0.0.1:7890`（改成你的代理地址），然后重启 |
| 首屏没有动画 | 系统开启了「减少动态效果」时会直接显示最终画面，这是有意设计 |
| 想确认动画是否流畅 | 在任意页面地址后加 `?fps`，左下角会显示实时帧率 |

## 7. 手动启动（不使用脚本）

```powershell
cd frontend; npm ci; npm run build; cd ..
cd backend; .\mvnw.cmd -DskipTests package; cd ..
java -jar backend\target\video-compressor-backend-1.0.0.jar
```

在仓库根目录执行最后一条命令，以便读取根目录的 `.env` 并使用 `./data`。

## 8. 数据库与迁移

表结构由 Flyway 的版本化迁移脚本管理，应用启动时自动执行：

| 数据库 | 用途 | 迁移目录 |
|---|---|---|
| SQLite | 本地单机开发与演示（默认，`backend/data/app.db`） | `backend/src/main/resources/db/migration/sqlite` |
| PostgreSQL 16+ | 多人使用或公网部署（`prod` 配置） | `backend/src/main/resources/db/migration/postgresql` |

- 两个目录下同一版本号的脚本必须**等价**：表、列和约束相同。新增表结构时，两边各写一个 `V<n>__<说明>.sql`。
- 自动化测试会在真实的 PostgreSQL 16 进程上执行生产迁移，并走一遍主要的读写路径（`PostgresProfileTests`，使用嵌入式 PostgreSQL，不需要 Docker），保证线上运行的就是测试过的 SQL。
- **从旧版本升级**：以前由 `schema.sql` 建的 SQLite 数据库，第一次启动时会被标记为基线 0，再执行 V1 补齐缺少的表，原有数据保留（`SqliteUpgradeTest`）。升级前建议先复制一份 `app.db` 作为备份。

## 9. 生产部署（PostgreSQL）

> 本地 Windows 一键脚本只适合开发和演示。多人同时使用或对公网开放时，请使用 PostgreSQL，并放在 HTTPS 反向代理之后。

1. 准备 PostgreSQL 16+，创建数据库和账号：
   ```sql
   CREATE USER lumen WITH PASSWORD '请换成强密码';
   CREATE DATABASE lumen OWNER lumen;
   ```
2. 设置环境变量并以 `prod` 配置启动。数据库的三项和 `APP_JWT_SECRET` 在生产配置中没有默认值，缺任何一个，服务都会拒绝启动：
   ```bash
   SPRING_PROFILES_ACTIVE=prod
   LUMEN_DB_URL=jdbc:postgresql://127.0.0.1:5432/lumen
   LUMEN_DB_USER=lumen
   LUMEN_DB_PASSWORD=...
   APP_JWT_SECRET=...            # 足够长的随机字符串，不要与其它环境共用
   APP_DATA_DIR=/srv/lumen/data  # 可选，默认 ./data；上传文件、处理结果、交付包都存放在这里
   ```
   首次启动时会自动创建全部表。生产配置不会在已有的未知表结构上自动建立基线（`baseline-on-migrate=false`），并且禁用了 `flyway clean`。
3. **备份**：数据库只保存元数据，文件本身在 `APP_DATA_DIR/storage`，两者需要一起备份：
   ```bash
   pg_dump -Fc -U lumen lumen > lumen-$(date +%F).dump
   tar czf lumen-files-$(date +%F).tgz -C /srv/lumen/data storage
   ```
4. **恢复**（先停止服务）：
   ```bash
   pg_restore --clean --if-exists -U lumen -d lumen lumen-2026-10-19.dump
   tar xzf lumen-files-2026-10-19.tgz -C /srv/lumen/data
   ```
   恢复后启动服务，Flyway 会核对迁移版本。建议恢复到一台测试机上，确认能登录、能下载历史结果后，才算一次有效的恢复演练。

**目前尚未完成的生产项**：上面的备份/恢复流程还没有在真实的 PostgreSQL 服务器上演练过；病毒扫描（ClamAV）、公开接口限流和数据加密原本是为"公开表单"设计的，材料规范官目前没有对公网开放的入口，等以后加入"投稿自检链接"这类公开入口时再实现。

## 10. 学习播放器（可选）

需要三项：

1. **阿里云百炼 API Key**（语音识别）：在百炼控制台创建 API Key，写入 `.env` 的 `DASHSCOPE_API_KEY=`（默认模型 `paraformer-v2`，可以用 `DASHSCOPE_ASR_MODEL` 修改）。音频会上传到百炼的临时存储，48 小时后自动删除。百炼说明这种临时存储只适合开发测试；正式上线、用量较大时，应改为上传到自己的 OSS。
2. **本地 OCR**（RapidOCR）：建一个独立的 Python 环境，可以放在 D 盘以节省 C 盘空间。复用系统里已安装的 onnxruntime，约占 170MB：
   ```powershell
   python -m venv --system-site-packages D:\LumenTools\ocr-venv
   D:\LumenTools\ocr-venv\Scripts\python.exe -m pip install rapidocr
   ```
   后端会自动在 `D:\LumenTools\ocr-venv` 中查找。放在别处时，在 `.env` 中设置 `OCR_PYTHON=` 为该环境的 python.exe。第一次识别时会下载模型（几十 MB）。
3. **DeepSeek Key**：与智能模式共用。

代码沙盒运行在浏览器里，Pyodide 运行时随前端一起构建（`npm run build` 会把它复制到 `dist/pyodide/`，约 12MB），不依赖任何 CDN。

