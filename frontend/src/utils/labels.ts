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
  converting: '转码中',
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
  OUTPUT_INVALID: '输出没有通过独立核验（见下方检查项），可以重试；反复出现请换一种输出格式。',
}
