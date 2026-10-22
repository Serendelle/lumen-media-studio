# 文档与 PDF 格式互转

`/workspace/documents` 和媒体工作台的「PDF / 文档转换」使用同一套面板及异步任务。支持 DOCX、XLSX、HTML、OFD、PDF、PNG、JPG 七种格式，提供 42 个不同格式间的转换方向。实际安装缺少 LibreOffice、Chromium 或 OFD worker 时，相应路线显示原因并禁止提交；纯 Java 的图片、PDF 和可编辑内容转换仍可使用。

## 转换方式

| 来源与目标 | 实际处理方式 |
|---|---|
| DOCX / XLSX / HTML → PDF | LibreOffice 或隔离配置的 Chromium 打印；保留 Excel 工作表选择与 HTML 纸张设置 |
| DOCX / XLSX / HTML 相互转换 | 默认提取段落、可见单元格与静态表格；页面引擎可用时也可选择图片版式模式保留外观，适用于纯图片 Word 等文件 |
| PDF → DOCX / XLSX / HTML | 默认逐页图片保留外观；可选提取可编辑内容，读取真实文本层，不执行 OCR；无文字层时明确失败 |
| OFD → 其他格式 | 隔离 OFDRW 进程逐页渲染，再生成目标；DOCX / XLSX / HTML 中的页面是图片 |
| 其他格式 → OFD | 渲染为逐页图片后生成实际 OFD 包，并校验页数与页面尺寸 |
| PNG / JPG → DOCX / XLSX / HTML | 图片嵌入文档或独立 HTML；不声称文字可编辑 |
| 多张 PNG / JPG → PDF / OFD / DOCX / XLSX / HTML | 按选择顺序合并；可上下调整页序 |
| 文档 → PNG / JPG | 逐页输出 ZIP，即使只有一页也返回 ZIP；内含 `manifest.json` 页序、字节数和 SHA-256 |
| PNG ↔ JPG | 单图直接生成实际图片；多图输出图片 ZIP；JPG 使用白色背景合成透明区域 |

Word / Excel 目前指 DOCX / XLSX，旧版 DOC / XLS 需要先另存为新格式。OFD/PDF 的数字签名验证效力不会随转换迁移。OFD 转换采用页面图像，不保留可编辑文本层。页面图片无法恢复原始表格公式；Excel 可编辑转换使用已保存的公式缓存值，不计算公式、不请求外部数据。

## 限制与核验

- 每次一个文档，或最多 100 张 PNG/JPG；输入总大小上限 200MiB。上传 DOCX / XLSX 上限 30MiB、HTML 5MiB、OFD 100MiB。
- 页面渲染转换最多 100 页，可选 96 / 150 / 300 DPI；每页最多 1200 万像素、长边 6400 像素，大页面自动降低实际 DPI。图片输入最多 4000 万像素；OFD 引擎另有每页 1600 万像素限制。
- 可编辑提取最多 200 页、100 万字符、10 万单元格。全程支持取消；转换计算过程设 120 秒限制，外部进程另有超时。
- 转换结果最多 200MiB，DOCX / XLSX / PDF / OFD / 图片均重新解析；图片 ZIP 逐项校验实际编码、尺寸和摘要。任务报告显示模式、原始格式、结果大小与核验记录。
- Word 页面最长边超过约 550mm 时缩放至 Word 可表达的范围；图片未携带可靠打印尺寸时按 96 DPI 计算页面大小。
- 原始上传只读，外部引擎只接触工作目录副本。HTML 清理脚本、本地路径和外链；OFD 先检查 ZIP 路径、展开体积和 XML 外部实体，再交给子进程。
- 原文件和结果仍按现有用户权限隔离，下载票据与后台任务属于提交者。

## API 与依赖

`GET /api/v2/documents/matrix` 返回每条路线的 `available`、`reason`、`modes`、`defaultMode`、`outputKind`、说明与限制。`POST /api/v2/documents/convert` 接收：

```json
{
  "sourceFileId": "上传后返回的文件 ID",
  "targetFormat": "docx",
  "options": { "mode": "layout", "density": 150 }
}
```

多图时用 `sourceFileIds` 数组替换 `sourceFileId`。响应为任务 ID；任务查询、取消和结果下载复用现有 API。`mode` 为 `layout` 或 `editable`，可用模式以路线矩阵为准，不能将图片伪报为已识别文字。

OFD 组件位于 `backend/ofd-worker`，单独打包为 `lumen-ofd-worker.jar`，不向主应用引入 PDFBox 2.x。分发时必须同时保留旁边的 `lib/` 目录（可替换的 UJMP 库及源码），许可与完整构建方式见 [组件说明](../backend/ofd-worker/README.md)。Windows 启动脚本自动构建；手动构建可在仓库根目录执行：

```powershell
cd backend/ofd-worker
../mvnw.cmd -B -ntp package
```

默认从根目录或 backend 工作目录定位该 JAR，也可通过 `OFD_WORKER_JAR` 指定。Dockerfile 会单独打包并复制组件；云端容器仍需按部署文档进行实际启动验收。

## 本地验证（2026-10-22）

- `UniversalConversionTests.all42CrossRoutesWriteReadableFiles` 实际运行全部 42 条路线，使用真实 DOCX / XLSX / HTML / PDF / OFD / PNG / JPG 小样本，重新打开目标格式并检查原件哈希。
- API 回归验证 PDF 文本提取、扫描件明确拒绝可编辑模式、多图合并、图片 ZIP 页序与摘要、他人无权读取任务/文件，以及 AI 对真实输出类型的推断和损坏产物审核。
- 两页 PDF → 版式 Word → PDF，核对恰好两页、页面尺寸及每页内容；1000×100 窄图转 Word 核对图片宽高比与无额外空白页。
- 全量回归运行 210 项（不重复上述 42 路线用例），付费模型测试跳过 1 项；发现的 OFD 累计路径校验问题修复后，定向重跑文档/OFD 16 项全部通过并成功打包。其余媒体、协作、材料收集、数据库和本地 AI 回归通过。
- 前端类型检查、生产构建和 4 项转换边界测试通过；部署脚本 8 项检查通过。未进行浏览器视觉验收或云端容器验收。
- OFD worker 8 项测试通过，并用最终 JAR 独立进程验证图片、嵌入 Arial 字体、嵌入宋体 TTC 中文与矢量边框；核对文字区域实际墨迹和尺寸。主应用也重新核验了文字 OFD 的包结构与输出 PDF 可渲染性。
- 最后补充图片版 DOCX → 版式 HTML / XLSX 回归，实际解码结果中的图片并确认源文件哈希不变；该用例与最终 OFD 复验共 6 项通过，随后打包并启动。

这些小样本用于验证转换方向和错误边界；复杂第三方文档的字体、特殊图元、公式与版式仍需检查实际转换结果。OFD 缺少内嵌字体时可能使用本机字体替代。
