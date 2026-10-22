# LUMEN V3 云端部署与维护

本文针对已有的腾讯云轻量应用服务器：Ubuntu 24.04、4 核 CPU、8GB 内存、100GB 磁盘。没有创建服务器、购买 SDK、开通对象存储或申请付费证书。应用代码继续兼容 Windows + SQLite；云端使用现有的 `prod` 配置和 PostgreSQL 16。

**验证状态：本次完成了部署文件、脚本安全测试和配置解析；当前开发机没有 Docker / Podman，尚未构建或启动这些镜像。真实容器启动、重启持久化、Nginx 配置检查、HTTPS 和备份恢复演练仍须在有 Docker 的服务器完成。下面的验收命令是待执行步骤，不是已经通过的结果。**

## 1. 架构与文件

```text
浏览器 ── HTTPS 443 ── Nginx：Vue + React 构建产物
                          │ /api/**，包括 SSE、匿名收集和表格
                          ▼
                     Spring Boot / Java 17
                          ├── PostgreSQL 16：内部网络，无宿主端口
                          └── 持久卷：真实上传原件、结果、项目版本和交付包
```

| 文件 | 用途 |
|---|---|
| `Dockerfile` | `api`、`web` 两个构建目标；Maven / Node 仅出现在构建阶段 |
| `compose.yaml` | PostgreSQL、API、Nginx、资源上限、健康检查、持久卷 |
| `deploy/compose.https.yaml` | 用 80 / 443 替换仅回环可用的测试端口，挂载证书 |
| `deploy/.env.example` | 无真实密钥的生产配置模板 |
| `deploy/init-config.py` | 独立生成数据库密码和 JWT 密钥，不回显、不覆盖现有配置 |
| `deploy/nginx/` | 页面路由、API / SSE 代理、上传限制、日志脱敏、HTTPS |
| `deploy/backup.sh` / `restore.sh` | 停写维护窗口中的数据库与文件配对备份 / 恢复 |
| `deploy/verify-backup.py` | 校验摘要、数据库格式头和存储归档路径，拒绝符号链接 / 路径穿越 |
| `deploy/release.sh` | 构建新镜像、配对备份、更新版本、启动并等待健康检查 |
| `deploy/test_deploy.py` | 不依赖 Docker 的 8 项脚本安全测试 |

根目录 `.env` 是本机环境配置。云端使用独立的 `deploy/.env`，不会自动读取或复制本机密钥。`.dockerignore` 排除了 `.env`、密钥文件、所有运行数据、Git 目录及本机构建输出；前端构建不接收 AI 密钥。

## 2. 准备已有服务器

1. 在服务器配置私有仓库的只读部署凭据，再把本仓库检出至 `/srv/lumen`，后续命令均在该目录执行。不要把访问令牌写进 Git URL、脚本或镜像。
2. 按 [Docker 官方 Ubuntu 安装文档](https://docs.docker.com/engine/install/ubuntu/) 安装 Docker Engine、Buildx 和 Compose 插件。Ubuntu 24.04 属于该文档支持的系统。Compose 至少为 **2.24.4**，HTTPS 覆盖文件使用该版本起提供的 [`!override` 合并语法](https://docs.docker.com/reference/compose-file/merge/)。宿主机还需要 `python3`、`git` 和常见 POSIX 命令。
3. 准备解析到服务器的域名和已有的有效 TLS 证书；下面示例域名必须换成自己的域名。
4. 在 [轻量应用服务器防火墙](https://cloud.tencent.com/document/product/1207/44577) 只开放业务所需的 80 / 443；SSH 22 限定管理来源。不要开放 PostgreSQL 5432 或 API 8080。Docker 发布端口与宿主 UFW 的关系按上面的 Docker 官方文档核对。

```bash
cd /srv/lumen
docker version
docker compose version
python3 --version
df -h . /var/lib/docker
python3 deploy/init-config.py --origin https://lumen.example.com
```

生成的 `deploy/.env` 在 Linux 上权限为 `0600`。重复运行初始化命令会拒绝覆盖，避免误换数据库密码或使现有 JWT 失效。后续编辑使用受控终端，例如 `nano deploy/.env`，不要输出整个文件到日志。

默认 `APP_ALLOW_REGISTRATION=false`。首次创建负责人及受邀成员账号时，可在维护窗口临时改为 `true`，启动后通过正常注册页创建账号，再改回 `false` 并重建 API / Nginx 服务。账号仍使用现有的 BCrypt 和 JWT 体系。

## 3. 首次构建与本机检查

```bash
sh deploy/compose.sh config --quiet
sh deploy/compose.sh build api
sh deploy/compose.sh build web
sh deploy/compose.sh up -d --wait --wait-timeout 300
sh deploy/compose.sh ps
sh deploy/compose.sh exec -T api curl --fail --silent http://127.0.0.1:8080/actuator/health
curl --fail http://127.0.0.1:8080/healthz
```

`config --quiet` 仅验证配置。不要把不带 `--quiet` 的完整渲染结果贴到工单：其中包含容器环境变量。

首次构建需要从 npm、Maven、Debian 和容器镜像仓库下载依赖。两个构建目标分别执行，减少 8GB 机器同时构建的内存压力。后端镜像打包时跳过测试；发布前必须另外执行应用测试，不能把构建成功当成功能验收。

HTTP 测试端口默认仅绑定 `127.0.0.1:8080`，可通过 SSH 隧道检查页面：在自己的电脑执行 `ssh -L 8081:127.0.0.1:8080 管理账号@服务器地址`，再打开 `http://127.0.0.1:8081`。多人分享链接应在最终 HTTPS 域名下生成。

容器内临时需要验证图片 / Office 工具时，先查看实际工具版本：

```bash
sh deploy/compose.sh exec -T api java -version
sh deploy/compose.sh exec -T api ffmpeg -version
sh deploy/compose.sh exec -T api magick -version
sh deploy/compose.sh exec -T api gs --version
sh deploy/compose.sh exec -T api exiftool -ver
sh deploy/compose.sh exec -T api soffice --version
```

镜像使用 Debian trixie 的 [ImageMagick 7 软件包](https://packages.debian.org/trixie/imagemagick)，与应用调用的 `magick` 命令匹配，并显式安装 SVG 等格式需要的附加编码器。Java 运行时来自 Eclipse Temurin 17；宿主系统保持 Ubuntu 24.04。

## 4. 启用 HTTPS

按照 [腾讯云 Nginx 证书部署文档](https://cloud.tencent.com/document/product/400/35244) 从已经持有的证书包取得证书链和私钥。将证书链存为 `data/deploy/tls/fullchain.pem`，私钥存为 `data/deploy/tls/privkey.pem`；可从个人电脑安全上传，不需要给应用云账号权限。

```bash
mkdir -p data/deploy/tls
# 将已有证书安全上传至上述两个路径后执行：
sudo chown root:101 data/deploy/tls data/deploy/tls/fullchain.pem data/deploy/tls/privkey.pem
sudo chmod 750 data/deploy/tls
sudo chmod 640 data/deploy/tls/fullchain.pem data/deploy/tls/privkey.pem
sed -i 's/^LUMEN_HTTPS=0$/LUMEN_HTTPS=1/' deploy/.env
sh deploy/compose.sh config --quiet
sh deploy/compose.sh run --rm --no-deps -T --entrypoint nginx web -t
sh deploy/compose.sh up -d --force-recreate --wait --wait-timeout 300 api web
curl --fail https://lumen.example.com/healthz
```

Nginx 以 UID / GID 101 运行，通过只读挂载读取证书。证书目录和私钥不会进入构建上下文。HTTP 自动跳转到 HTTPS；HTTPS 响应启用 HSTS，证书必须真实有效。证书续期后先执行 `sh deploy/compose.sh exec -T web nginx -t`，成功后执行 `sh deploy/compose.sh exec -T web nginx -s reload`。

此配置直接在 Nginx 终止 TLS。若前面另加负载均衡或 CDN，需据实际可信代理配置来源地址，重新验证限流和 HTTPS 跳转；不能信任任意客户端传来的 `X-Forwarded-For`。

## 5. 资源、功能与配置边界

| 资源 / 配置 | 默认值 | 行为 |
|---|---|---|
| API 容器 | CPU 3、内存 5GiB、最多 512 个进程 | 上限包含 Java 与其 FFmpeg / Office 子进程 |
| Java | 堆 1536MiB、元空间 384MiB | 给外部处理程序留出内存 |
| PostgreSQL | CPU 0.75、内存 1GiB | `shared_buffers=256MB`、最大 40 连接；应用连接池 8 |
| Nginx | CPU 0.25、内存 256MiB | 静态前端和流式代理 |
| `APP_MEDIA_CONCURRENCY` | 1 | 沿用现有任务池，视频、图片、Office 共用队列 |
| `APP_MEDIA_QUEUE` / `APP_USER_ACTIVE_LIMIT` | 20 / 3 | 队列与个人活动任务上限 |
| `APP_FFMPEG_THREADS` | 2 | 限制单个 FFmpeg 的线程数 |
| `APP_TASK_TIMEOUT_MIN` | 30 | 沿用处理器的超时；Office / HTML 转换另有现有的 120 秒硬限制 |
| `APP_MIN_FREE_DISK_MB` | 10240 | 剩余磁盘不足 10GiB 时拒绝新处理任务 |
| `APP_MAX_UPLOAD_MB` | 1024 | 后端常规请求限制；Nginx 对匿名收集仍限制为 201MiB 请求 |
| `APP_RETENTION_HOURS` | 168 | 普通临时文件保留配置；项目文件另受业务保留规则控制 |
| `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` | 空 | 未配置则相关远端 AI / ASR 不可用，不会伪报执行成功 |

API 和 Nginx 使用只读根文件系统、移除 capabilities、启用 `no-new-privileges`，数据写入命名卷，少量临时文件写入受限 tmpfs。ImageMagick 的内存 / 磁盘 / 线程 / 页数等约束在 `deploy/imagemagick-policy.xml`；这些是基于 [官方安全策略机制](https://imagemagick.org/security-policy/) 配置的独立处理限制。

当前容器中的 Chromium 包装器为兼容容器环境使用 `--no-sandbox`，**没有启用 Chromium 内部沙箱**。应用现有的 HTML 清洗、禁脚本 / 禁网络转换参数和外层非 root 容器仍保留。对此有更强隔离要求时，应把浏览器转换拆到专用受限工作进程；这里不宣称进程级沙箱已经完成。

镜像默认没有安装 rembg / RapidOCR Python 环境，也不会在启动时自动下载模型。视频、音频、元数据、ImageMagick / Ghostscript PDF、LibreOffice、Chromium 相关工具随镜像安装；抠图与本地 OCR 应按实际需求另建受测镜像。功能是否可用以实际 `/api/system/capabilities` 返回的能力状态为准，未安装项应显示不可用。

没有配置或开通 COS。本次默认路径完整使用本地持久卷；数据库只有元数据，原件与各版本在应用卷。未来对象存储需另行配置受限凭据、上传票据、回调校验和备份策略；仅填写不存在的 COS 环境变量不会启用直传。

## 6. SSE、权限和日志

所有 `/api/**` 都转发给同一个 API，包括匿名收集、协作表格、任务流和项目事件。Nginx 显式保留 `Authorization`、`X-Participant-Token`、`Last-Event-ID`；关闭请求 / 响应缓冲和代理缓存，并设置流式读取超时。相关语义见 [Nginx 官方代理文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)。网关不承担业务角色授权，每次读取、写入、下载和事件授权仍由后端执行。

Nginx 替换外部传入的转发来源头，配合 Spring 的 native forwarding 还原实际来源 IP。匿名收集写入在网关额外按 IP 限流，每分钟 60 次、短时突发 20 次。API / 收集页禁止缓存；后台下载继续通过权限接口，持久卷不会以静态目录直接暴露。

```bash
sh deploy/compose.sh ps
sh deploy/compose.sh logs --since 15m --tail 200 api
sh deploy/compose.sh logs --since 15m --tail 100 web
sh deploy/compose.sh logs --since 15m --tail 100 db
docker stats --no-stream
docker system df
df -h /var/lib/docker
```

容器日志每服务最多保留 `3 × 10MB`。Nginx 访问日志只记录来源 IP、方法、状态、字节和耗时，省略整个 URL、查询串、Referrer 和认证头；API / 收集页关闭可能带令牌的 Nginx 请求错误日志，结合状态码与后端日志排查。不要临时启用会记录完整请求或请求体的 debug 日志，也不要分享未脱敏的数据库转储。

`/actuator/**` 在 Nginx 外部返回 404，健康检查在容器内部执行。健康检查可发现进程 / 数据库 / 核心媒体工具或磁盘异常；Compose 的 `restart` 针对进程退出，不会仅凭 `unhealthy` 自动完成故障恢复。

当前是单 API 实例部署，复用进程内 SSE 连接。不能直接把 API 扩容成多副本并宣称跨实例在线状态和即时通知已同步；这需要进一步接入共享事件通道等设施。

## 7. 配对备份与恢复

命名卷中的数据库与文件要一起备份。容器重建不会删除命名卷；普通磁盘损坏或管理员删除卷仍需依赖异机备份。**不要执行 `docker compose down -v`，该命令会删除持久卷。**

先等待活动任务完成或在界面取消，再执行：

```bash
sh deploy/backup.sh
python3 deploy/verify-backup.py data/deploy/backups/实际备份时间
```

脚本在停写维护窗口停止 Nginx / API，生成 `database.dump`、`storage.tar.gz`、镜像清单、代码版本和 `SHA256SUMS`。数据库使用真实 `pg_dump --format=custom`，文件从应用持久卷读取；成功后恢复原来运行的服务，`--leave-stopped` 则留在维护状态。任务检查会在停服前后各执行一次，避免恰好到达的新任务被当作已经完成。部分失败产物留在受限权限的 `.partial-*` 目录，不能用作有效备份。

将有效备份加密保存到已有的另一台设备或已有备份存储，并单独安全保存 `deploy/.env`、证书和旧镜像。脚本不会自动上传数据、创建付费备份存储或打印密钥。SHA-256 用于检测损坏，不代替备份来源的信任或加密。

恢复应先在独立测试主机演练；以下显式参数表示用备份**替换当前数据库数据**：

```bash
sh deploy/compose.sh up -d db
sh deploy/restore.sh /srv/lumen/data/deploy/backups/实际备份时间 --replace-existing-data
# 选定与备份兼容的 LUMEN_RELEASE、代码版本和部署配置后：
sh deploy/compose.sh up -d --no-build --force-recreate --wait --wait-timeout 300 api web
```

恢复前校验所有摘要、`PGDMP` 格式头和完整存储归档路径，拒绝绝对路径、`..`、符号链接及硬链接。随后停服，重建应用数据库并恢复转储，再恢复文件；任何步骤失败都让 API / Nginx 保持停止，避免对外提供数据库 / 文件不一致的状态。旧文件目录会保留为 `/data/storage.before-restore.<时间>`，恢复成功并核对数据后再由管理员处理，需要提前留足磁盘空间。

## 8. 发布与回滚

先完成回归测试并检出确定的代码版本，再使用唯一版本标签：

```bash
sh deploy/release.sh v3-20261021-1
sh deploy/compose.sh ps
sh deploy/compose.sh logs --since 10m --tail 100 api
```

发布脚本先构建新 `lumen-api:<标签>` / `lumen-web:<标签>` 镜像，失败时不停止旧服务；之后进行停写配对备份，更新 `LUMEN_RELEASE` 并替换两个服务。新 API 执行真实 Flyway 迁移。若健康检查失败，脚本停止对外服务并返回错误，不会假报发布成功。

正式部署应保存镜像 ID / digest，逐步固定基础镜像 digest，并保留上个可用的 API / Web 镜像。执行 `docker image prune` 前检查旧镜像是否还用于恢复；不要同时更新 PostgreSQL 大版本与应用版本。

若没有数据库迁移，核对兼容性后可把 `deploy/.env` 的 `LUMEN_RELEASE` 改回已有旧标签，并以 `--no-build --force-recreate` 启动。**若新版本已经执行数据库迁移，不能把更换旧 JAR / 镜像当作完整回滚。** 应使用升级前的配对备份恢复数据库与文件，再启动对应的旧镜像；恢复会舍弃备份时间之后的新写入，因此发布期间保持维护窗口。优先考虑可验证的向前修复。

## 9. 服务器验收步骤与本次结果

开发机已实际执行：

- `python -m unittest discover -s deploy -p 'test_*.py' -v`：**8 / 8 通过**，覆盖密钥生成不回显、不覆盖配置、无效 origin、受限本地 HTTP、备份摘要、路径穿越 / 链接及格式错误。
- `bash -n`：5 个 shell 脚本语法通过。
- YAML 解析（包括 HTTPS 的 `!override`）、数据库不暴露端口 / 持久卷 / 资源上限检查、ImageMagick XML 与 Python 语法检查通过。

这些检查不能替代以下尚未执行的真实环境验收：

1. 在目标服务器执行首次构建、`nginx -t`、健康检查和 HTTPS 检查，记录镜像和工具实际版本。
2. 建立真实账号 A、B、C，验证邀请 / 权限 / 撤权、真实文件下载、版本校验和 SSE 重连。
3. 上传一个实际文件并记下项目 ID、版本和 SHA-256；执行 `sh deploy/compose.sh restart api web` 后再次登录、核对原字节和版本。
4. 执行 `sh deploy/compose.sh down` 后 `up -d --wait`（**不带 `-v`**），核对数据库、成员、项目、历史文件仍在。
5. 在测试主机完成配对备份恢复演练，实测历史下载、文件 SHA-256、角色、活动记录和交付包。
6. 实测视频压缩、音轨提取、Office / 图片 / PDF 转换；远端 AI 仅在账号负责人同意调用并已配置密钥时测试。
7. 在两个真实设备 / 腾讯文档账号中验证外部腾讯文档编辑。LUMEN 的链接和手动导入版本不等于腾讯文档内容自动同步。

本次没有服务器登录凭据、域名证书或可用 Docker 运行时，故没有远程上线、公开端口修改、真实容器重启 / 恢复结果，也没有付费 AI / SDK 调用。部署代码已可供审阅，场景 G 的运行验收保持未完成。
