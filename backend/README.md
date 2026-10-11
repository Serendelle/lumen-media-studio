# Backend · Lumière Media Studio

Spring Boot 3.5 / Java 17 媒体处理服务，专业模式和 AI 模式共用这一套服务。

- API 契约：[`../docs/api-contract.md`](../docs/api-contract.md)（运行时文档：`/swagger-ui.html`）
- 安全审计：[`../docs/backend-audit.md`](../docs/backend-audit.md)
- 测试报告：[`../docs/test-report.md`](../docs/test-report.md)

## 结构

```
auth/        注册/登录（BCrypt + HS256 JWT）、下载票据、登录限流
common/      统一错误码与异常处理
config/      配置绑定（app.*）、安全、数据目录初始化
controller/  REST 接口（原有路径 /api/video|audio|image|pdf|metadata，新增 /api/files、/api/tasks、/api/system）
dto/         请求 DTO（Bean Validation）
media/       存储、类型检测、ffprobe、进程执行、任务引擎、SSE
media/handler/  各类处理器：压缩/转换/水印/音频/图片/抠图/PDF/元数据
```

## 运行

```bash
# 在仓库根目录准备 .env（参考 ../.env.example），然后：
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
./mvnw test                     # 需要本机已安装 FFmpeg 等工具；缺少工具的用例会显示为 skipped
```

数据（SQLite `app.db`、上传与输出文件）默认存放在 `./data`，可通过 `APP_DATA_DIR` 修改。外部工具会自动查找（先查 PATH，再查 winget 与常见安装目录），也可以在 `.env` 中指定路径。
