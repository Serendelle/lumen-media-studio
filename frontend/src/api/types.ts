// Types mirror docs/api-contract.md (backend is the source of truth).

export type TaskStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type MediaKind = 'VIDEO' | 'AUDIO' | 'IMAGE' | 'PDF' | 'ARCHIVE'

export type TaskType =
  | 'video.compress'
  | 'video.convert'
  | 'video.watermark'
  | 'audio.extract'
  | 'image.process'
  | 'image.removebg'
  | 'pdf.process'
  | 'metadata.scrub'
  | 'metadata.modify'

export interface UserView {
  id: number
  username: string
  displayName: string
}

export interface AuthResult {
  token: string
  expiresIn: number
  user: UserView
}

export interface MediaInfo {
  formatName?: string
  duration?: number
  bitrate?: number
  hasVideo?: boolean
  hasAudio?: boolean
  width?: number
  height?: number
  fps?: number
  videoCodec?: string
  videoBitrate?: number
  audioCodec?: string
  audioBitrate?: number
  audioChannels?: number
  audioSampleRate?: number
}

export interface FileView {
  fileId: string
  uniqueFilename: string
  originalFilename: string
  role: 'INPUT' | 'OUTPUT'
  mediaType: MediaKind
  mimeType: string
  size: number
  sourceTaskId?: string
  createdAt: number
  expiresAt: number
  info?: MediaInfo
}

export interface TaskOutput {
  fileId: string
  filename: string
  sizeBytes: number
  mediaType: MediaKind
  downloadUrl: string
}

export interface TaskView {
  taskId: string
  type: TaskType
  status: TaskStatus
  progress: number
  stage?: string
  message?: string
  errorCode?: string
  downloadFilename?: string
  origin: 'PRO' | 'AI'
  attempt: number
  output?: TaskOutput
  result?: Record<string, any>
  createdAt: number
  startedAt?: number
  finishedAt?: number
  /** filename is absent once the input file has expired */
  inputs?: { fileId: string; filename?: string; mediaType?: MediaKind }[]
}

export interface WorkspaceSummary {
  tasksByStatus: Record<TaskStatus, number>
  inputs: { count: number; bytes: number }
  outputs: { count: number; bytes: number }
  nextExpiryAt?: number
  recentTasks: TaskView[]
  recentFiles: FileView[]
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface CompressionPlan {
  feasible: boolean
  videoKbps: number
  audioKbps: number
  height: number
  bpp: number
  minFeasibleBytes: number
  reason?: string
  suggestions: { height: number; videoKbps: number; note: string }[]
}

export interface MetadataCapability {
  format: string
  writer: 'EXIFTOOL' | 'FFMPEG' | 'NONE'
  editableFields: string[]
  canScrubAll: boolean
  canScrubLocationOnly: boolean
}

export interface MetadataView {
  fileId: string
  filename: string
  mediaType: MediaKind
  groups: Record<string, Record<string, unknown>>
  sensitiveTags: string[]
  hasPrivacyData: boolean
  gps?: { latitude: number; longitude: number; mapUrl: string }
}

export interface SystemCapabilities {
  tools: Record<string, boolean>
  features: Record<string, boolean>
  ai: { configured: boolean; model: string }
  limits: { maxConcurrentTasks: number; perUserActiveTasks: number; retentionHours: number; registrationOpen: boolean }
  metadataCapabilities: Record<string, MetadataCapability>
}

export interface Ticket {
  ticket: string
  url: string
  downloadUrl: string
  expiresAt: number
}

/** Independent evidence attached to an output (task.result.verification). Status is VERIFIED or an error code. */
export interface Verification {
  status: 'VERIFIED' | 'TARGET_NOT_MET' | 'OUTPUT_INVALID' | string
  checks: { name: string; ok: boolean; expected: string; actual: string }[]
  measured: MeasuredMedia
}

export interface MeasuredMedia {
  actualBytes?: number
  targetBytes?: number
  formatName?: string
  duration?: number
  videoStreams?: number
  audioStreams?: number
  bitrate?: number
  videoCodec?: string
  width?: number
  height?: number
  fps?: number
  videoBitrate?: number
  audioCodec?: string
  audioSampleRate?: number
  audioChannels?: number
  audioBitrate?: number
}

export interface CompareSide {
  fileId: string
  filename: string
  mediaType: MediaKind
  sizeBytes: number
  readable: boolean
  measured: MeasuredMedia
}

export interface MediaCompare {
  source: CompareSide
  output: CompareSide
  deltaBytes: number
  savedPercent?: number
  durationDeltaMs?: number
}
