import type { TaskStatus, TaskType } from '@/api/types'

export const TASK_LABEL: Record<TaskType, string> = {
  'video.compress': '视频压缩',
  'video.convert': '格式转换',
  'video.watermark': '视频水印',
  'audio.extract': '音频提取',
  'image.process': '图片处理',
  'image.removebg': 'AI 抠图',
  'pdf.process': 'PDF 转换',
  'metadata.scrub': '隐私清理',
  'metadata.modify': '元数据编辑',
  'document.convert': '文档转换',
  'spec.deliver': '生成交付包',
  'lesson.analyze': '课程分析',
}

export const STATUS_LABEL: Record<TaskStatus, string> = {
  QUEUED: '排队中',
  RUNNING: '处理中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

const STAGE_LABEL: Record<string, string> = {
  encoding: '编码中',
  analyzing: '第一遍分析',
  converting: '转换中',
  preparing: '准备中',
  snapshot: '读取快照',
  'fill-xlsx': '填写汇总表',
  'fill-docx': '填写个人表',
  materials: '整理材料',
  watermarking: '叠加水印',
  extracting: '提取音轨',
  'loading-model': '加载模型',
  saving: '保存结果',
  scrubbing: '清理元数据',
  verifying: '独立核验',
  'writing-metadata': '写入元数据',
  packaging: '打包',
  done: '完成',
}

export function stageLabel(stage?: string): string {
  if (!stage) return ''
  if (stage.startsWith('processing')) return stage.replace('processing', '处理')
  return STAGE_LABEL[stage] ?? stage
}

/** What the user can actually do next for each failure code (shown next to the server's own message). */
export const ERROR_HINT: Record<string, string> = {
  TARGET_UNREACHABLE: '目标参数本身无法实现：调大目标体积、降低分辨率或音频码率后重新提交。',
  MEDIA_UNREADABLE: '文件无法解析，可能已损坏或编码不受支持；换一个导出版本再试。',
  CAPABILITY_UNSUPPORTED: '该格式不支持此操作，请换用其它输出格式。',
  TOOL_UNAVAILABLE: '服务器缺少所需的处理工具，请按部署文档安装后重试。',
  PROCESS_TIMEOUT: '处理超时：可先压缩或截短源文件，或在服务器上调大任务超时时间。',
  PROCESS_FAILED: '处理工具报错退出，可以重试；若反复失败，请用追踪号查看服务器日志。',
  INTERRUPTED: '服务重启打断了任务，直接重试即可。',
  INSUFFICIENT_DISK: '服务器磁盘空间不足，清理文件后重试。',
  QUEUE_FULL: '处理队列已满，稍后重试。',
  TOO_MANY_TASKS: '进行中的任务过多，等已有任务完成后再提交。',
  NOT_FOUND: '输入文件已过期或被删除，请重新上传后提交新任务。',
  AI_UNAVAILABLE: 'AI 服务未配置或暂不可用，可改用专业模式完成同样的处理。',
  CANCELLED: '任务已被取消，可以重试。',
  TARGET_NOT_MET: '已自动降码率重试一次仍超出目标：调大目标体积、降低分辨率或去掉音频后重新提交。原文件未受影响。',
  AUDIO_STREAM_MISSING: '这个文件没有音轨，换一个含声音的视频或音频文件。',
  UNSUPPORTED_DOCUMENT: '支持 DOCX、XLSX、单个 UTF-8 HTML、OFD、PDF、PNG 和 JPG；旧版 .doc/.xls 请先另存为新格式。',
  DOCUMENT_CORRUPTED: '文档结构损坏或可疑（例如压缩炸弹、非法路径），请用对应应用重新另存后上传。',
  DOCUMENT_PASSWORD_REQUIRED: '文档设置了打开密码，请先在 Office / PDF 软件中取消密码后再上传。',
  DOCUMENT_CONVERSION_FAILED: '转换引擎没有生成有效的目标文件，可以重试；反复失败请检查原文件能否正常打开。',
  DOCUMENT_CONVERSION_TIMEOUT: '转换超过 120 秒被中止，文档可能过大或过于复杂，可拆分后再转换。',
  CONVERTER_UNAVAILABLE: '服务器缺少当前路线所需的转换组件（如 LibreOffice、Chrome / Edge 或 OFD worker），请检查支持的转换路线。',
  ASR_UNAVAILABLE: '语音识别未配置或暂时不可用：在后端 .env 中设置 DASHSCOPE_API_KEY，或检查网络与阿里云账户。',
  ASR_FAILED: '语音识别没有成功（例如音频无法下载或没有讲话声），可以重试。',
  LESSON_INVALID: 'AI 整理出的课程结构没有通过时间与结构校验，可以重试一次。',
  OUTPUT_INVALID: '输出没有通过独立核验（见下方检查项），可以重试；反复出现请换一种输出格式。',
}
