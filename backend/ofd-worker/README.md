# OFD 转换组件

本模块使用 OFDRW 2.4.0 实际读写 OFD，作为独立 Java 17 进程运行，避免 OFDRW 所需的 PDFBox 2 与应用的 PDFBox 3 冲突。它不访问在线转换服务，也不需要付费 SDK。构建：在本目录执行 `../mvnw package`（Windows 使用 `..\mvnw.cmd package`），产物为 `target/lumen-ofd-worker.jar` 和必须保留相对位置的 `target/lib/`。Windows 启动脚本和 Docker 构建会自动构建、安装此组件。

- OFD → PDF：每页以 150 DPI 渲染为图像后写入真实 PDF，保留页面物理尺寸；每页最多 1600 万像素。PNG/JPG 由主应用继续渲染 PDF。
- PDF/图片 → OFD：图片写入真实 OFD 页面；PDF 保留每页物理尺寸，普通图片按 150 DPI 换算尺寸。
- 输出以版式图像为主，文字不可编辑，也不提供 OCR。数字签名及签章不转移、不构成有效签名；原文件不被修改。
- 单个 OFD 支持 1—200 页、单文档包，解压总量上限 256 MiB、单个 XML 上限 4 MiB、累计 XML 上限 32 MiB/25 万元素。拒绝路径穿越、压缩炸弹、DTD/实体、过深嵌套、外部文件与网络资源引用。解析前自行安全解包；签名资源入口被移除以避免递归解析嵌套签章包。
- 主应用限制转换进程为 768 MiB 堆、2 个 CPU、2 分钟，并支持取消。输出由真实 OFD/PDF 解析器重新打开、核验页数和尺寸。

OFDRW 使用 Apache-2.0 许可。未使用的 iText 转换器及其 AGPL/商业许可依赖已从依赖树排除。`CommonUtil` 和 `FontLoader` 在此模块中以源码级单位换算、实际 TrueType/OpenType/TTC 字体加载适配器替代，解决上游公共工具类在图像/文字渲染路径中仍强制链接 iText 的问题，不包含任何 iText 实现。优先使用 OFD 内嵌字体，缺失时仅在受信系统字体目录匹配和替换。

OFDRW 图像渲染器使用 UJMP 0.3.0，许可为 LGPL-3.0。构建将其保留为 `lib/ujmp-core-0.3.0.jar` 独立库，同时分发 `lib/ujmp-core-0.3.0-sources.jar` 对应源码，原始许可和版权公告位于该库 `META-INF/LICENSE.TXT`、`META-INF/NOTICE.TXT` 中。使用者可自行修改、重建并替换该库，不受应用接口以外的额外限制；本组件提供完整 Maven 配置以供重新链接。旧的、未被渲染流程使用的 `org.json:20141113` 依赖也已排除。其他依赖的许可/公告保留在 worker JAR 的 `META-INF` 中。发布时须保留本模块源码、Maven 配置和 `lib/`，不能仅复制 worker JAR。官方项目与源码：

- https://github.com/ofdrw/ofdrw
- https://github.com/ofdrw/ofdrw/blob/master/LICENSE
- https://github.com/ujmp/universal-java-matrix-package
- https://pdfbox.apache.org/

本模块的端到端测试生成两页、不同尺寸的彩色图片，执行 图片 → OFD → PDF，再重新渲染 PDF 检查颜色与页数/页面尺寸；安全测试覆盖路径穿越、XXE 和网络资源。
