# 后端自动化测试报告

- 执行时间：2026-10-11 22:33（本地）
- 环境：Windows 11 Home 10.0.26200，JDK 17.0.18（Temurin），Maven 3.9.9
- 外部工具（真实调用，未使用 Mock）：FFmpeg 9.0.2，ExifTool 13.59，ImageMagick 7.1.2-31，Ghostscript 10.08.0，rembg 2.0.85（u2net 模型）
- 执行命令：

```bash
cd backend
mvn -B test
```

## 1. 结果（最终一次完整执行）

| 指标 | 数值 |
|---|---|
| 测试总数 | **60** |
| 通过 | **60** |
| 失败 | 0 |
| 错误 | 0 |
| 跳过 | 0 |
| 实际通过率 | **100%**（60 / 60） |
| 总耗时 | 1 分 19 秒（`real 1m18.962s`） |

> 数据来自 Maven Surefire 的输出 `Tests run: 60, Failures: 0, Errors: 0, Skipped: 0` 与 `BUILD SUCCESS`。jqwik 的每个 `@Property` 计为 1 个测试，实际执行的随机样本数见下表（tries）。

| 测试类 | 类型 | 用例数 | 结果 | 耗时 |
|---|---|---|---|---|
| `api.ApiSecurityTests` | MockMvc 集成（完整 Spring 上下文 + SQLite + 真实工具） | 14 | 14 ✅ | 16.4 s |
| `media.MediaPipelineTests` | MockMvc 集成 + 真实媒体处理，输出由 ffprobe/ExifTool 独立核验 | 17 | 17 ✅ | 51.9 s |
| `media.ProcessRunnerTest` | 单元测试（真实 FFmpeg 进程） | 5 | 5 ✅ | 3.4 s |
| `media.FileTypeDetectorTest` | 单元测试（真实媒体字节） | 4 | 4 ✅ | 0.2 s |
| `auth.SecurityPrimitivesTest` | 单元测试 + 属性测试（500 tries） | 6 | 6 ✅ | 0.2 s |
| `media.CompressionPlannerProperties` | jqwik 属性测试（2000 / 1000 / 1000 / 500 tries + 2 个示例） | 6 | 6 ✅ | 0.2 s |
| `media.handler.FfmpegProgressProperties` | jqwik 属性测试（3000 / 1000 tries + 2 个示例） | 4 | 4 ✅ | 0.1 s |
| `media.StorageNameProperties` | jqwik 属性测试（5000 / 2000 / 2000 tries + 示例） | 4 | 4 ✅ | 0.3 s |

### 覆盖的场景维度

| 维度 | 代表用例 |
|---|---|
| 正常输入 | 每类处理器至少一条成功路径：CRF/两遍编码压缩、5 种音频格式、webm 转码、水印、图片转 JPG/WebP/AVIF、批量 zip、元数据写入、隐私清理、PDF 双向转换、AI 抠图 |
| 非法输入 | crf=99、`targetFormat` 含路径、非法水印透明度、非法时间格式、GPS 越界、畸形 JSON、未知 tool |
| 空值 | 空文件上传、缺少 `filenames`、空 `uniqueFilename`、空令牌 |
| 极值 | 体积目标 0.05MB（不可实现）、PNG 目标 1KB、进度计算输入 0/NaN/∞、随机字符串文件名 |
| 并发 | 6 线程使用相同幂等键提交（只生成 1 个任务）、6 线程并发注册同一用户名（只有 1 个成功）、用户配额（第 5、6 个任务返回 429） |
| 未授权 | 未带令牌、伪造签名的 JWT、格式错误的令牌、跨文件使用票据、过期票据、篡改过的票据 |
| 错误资源访问 | 他人的文件/任务/下载/票据/删除/SSE 均返回 404；路径形式的 ID 返回 404；文件过期清理后返回 404 |
| 失败恢复 | 输入文件丢失时任务失败，恢复文件后重试成功；服务崩溃后遗留的 RUNNING 任务被标记为 INTERRUPTED；运行中的任务可取消、可重试；工作目录被清理 |

## 2. 执行历史（全部真实执行）

| 轮次 | 总数 | 失败 | 通过率 | 说明 |
|---|---|---|---|---|
| 第 1 轮 | 59 | 6 | 89.8% | 发现 4 个产品缺陷和 2 处测试假设错误，见第 3 节 |
| 第 2 轮 | 59 | 1 | 98.3% | 幂等键并发冲突仍返回 500（异常类型捕获不全） |
| 第 3 轮（最终） | 60 | 0 | 100% | 修复后新增 1 个并发注册测试 |

## 3. 测试发现并修复的缺陷

| 编号 | 发现用例 | 缺陷 | 修复 |
|---|---|---|---|
| T-01 | `FileTypeDetectorTest.rejectsTextAndExecutables` | Tika 无法从内容识别时会按文件扩展名回退，空文件被识别为 PNG（审计 N-01） | 只按内容检测 |
| T-02 | `concurrentSubmitsWithSameIdempotencyKey…`、`perUserActiveTaskLimitIsEnforced` | 并发写入时出现 `SQLITE_BUSY`，接口返回 500（N-02） | SQLite 改为单连接 |
| T-03 | `concurrentSubmitsWithSameIdempotencyKey…`（第 2 轮） | 唯一索引冲突被报为 `JpaSystemException`，未被捕获（N-03） | 捕获后查询胜出的记录并返回；注册接口同样处理 |
| T-04 | `CompressionPlannerProperties.minFeasibleSizeIsActuallyFeasible` | jqwik 找到反例（324×343，8.11s，无音频）：报告的「最小可行体积」本身被判为不可行，原因是码率向下取整 | 最小体积按向上取整 +1 kbps 计算 |
| T-05 | `MediaPipelineTests.backgroundRemovalWithRembg` | rembg 的默认模型约 1GB，ONNX Runtime 内存分配失败（N-05） | 固定使用 u2net 模型 |
| — | `ProcessRunnerTest.overwritePrompt…` | **测试假设错误**：原以为 stdin 关闭后 FFmpeg 会拒绝覆盖文件，实际 FFmpeg 9 会继续执行。H-04 要验证的是「不会卡住」，这一点成立（0.08 秒内退出） | 修正断言 |

冒烟测试阶段还发现并修复了 N-04（任务入队早于事务提交）和 N-06（JPEG 1:1 密度导致 PDF 页面尺寸巨大），详见 `backend-audit.md` 第 5 节。

## 4. 未覆盖或未验证的范围（如实列出）

| 范围 | 现状 |
|---|---|
| 行/分支覆盖率 | 未接入 JaCoCo，**未统计覆盖率百分比** |
| 队列已满（503 `QUEUE_FULL`）、磁盘不足（503 `INSUFFICIENT_DISK`） | 逻辑存在，**未用自动化测试触发** |
| 按媒体类型的体积上限（例如图片 50MB） | 未构造超大文件测试；413 路径由 Spring multipart 配置与 `UploadService` 保证 |
| 上传中途断开 | 未测试 |
| SSE 实时推送 | 只测试了「先推快照、结束后关闭连接」以及越权访问；持续的 `progress` 事件流将在前端联调阶段验证 |
| 性能与压力 | 未做吞吐量或长时间稳定性测试 |
| 非 Windows 平台 | 只在 Windows 11 上执行过 |
| 恶意构造的 PDF（超大页面） | 由超时、取消和配额兜底，未单独测试（审计 M-13，接受残余风险） |
| 定时清理任务的调度 | 直接调用 `purgeExpired()` 验证了清理逻辑，未验证定时触发 |

## 5. 测试数据说明

所有媒体文件都在测试运行时用 FFmpeg/ExifTool 现场生成（`TestMedia`，输出到 `backend/target/test-media`），仓库中不提交二进制测试文件：

- `demo.mp4`：6 秒 1280×720 H.264 6Mbps + AAC 192k
- `photo.jpg`：1600×1200，含 GPS（31.2304N, 121.4737E）与相机型号 `TestCam`
- `logo.png`、`song.mp3`
- `truncated.mp4`：截断的 MP4（文件头魔数正确，内容损坏）

缺少对应工具的机器上，依赖这些工具的用例会显示为 **skipped**，而不是通过。
