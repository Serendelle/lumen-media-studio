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
