// Types mirror docs/api-contract.md (backend is the source of truth).

export type TaskStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type MediaKind = 'VIDEO' | 'AUDIO' | 'IMAGE' | 'PDF' | 'ARCHIVE' | 'DOCUMENT'

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
  | 'document.convert'
  | 'spec.deliver'
  | 'lesson.analyze'

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
  origin: 'PRO' | 'AI' | 'SPEC'
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

export interface ConversionRoute {
  from: string
  to: string
  engine: string
  available: boolean
  taskType: string
}

export interface ConversionMatrix {
  routes: ConversionRoute[]
  notSupported: string[]
}

/** GET /api/v2/documents/{id}/inspect — fields depend on the format. */
export interface DocumentInspection {
  fileId: string
  filename: string
  kind: MediaKind
  format: 'docx' | 'xlsx' | 'html' | 'pdf'
  sizeBytes: number
  warnings: string[]
  paragraphs?: number
  tables?: { index: number; rows: number; cols: number }[]
  pageSize?: string
  pageWidthMm?: number
  pageHeightMm?: number
  pageCount?: number
  fonts?: string[]
  missingFonts?: string[]
  sheets?: { index: number; name: string; hidden: boolean; usedRows: number; usedCols: number; mergedRegions: number; printArea?: string; landscape: boolean }[]
  title?: string
  willRemove?: Record<string, number>
}

export interface PdfPagePreview {
  page: number
  pageCount: number
  pageSize: string
  imageTicketUrl: string
  expiresAt: number
}

// ---------------- 材料规范官 (/api/v2/specs) ----------------

export type SlotKind = 'VIDEO' | 'AUDIO' | 'IMAGE' | 'DOCUMENT'

export interface SpecRules {
  extensions?: string[]
  maxBytes?: number
  maxWidth?: number
  maxHeight?: number
  minWidth?: number
  minHeight?: number
  minDurationSec?: number
  maxDurationSec?: number
}

export interface SpecSlot {
  key: string
  label: string
  kind: SlotKind
  required: boolean
  rules: SpecRules
  evidence?: string
  evidenceVerified?: boolean
}

export interface MaterialSpec {
  title: string
  sizeUnit: 'DECIMAL' | 'BINARY'
  slots: SpecSlot[]
  naming: { pattern?: string; evidence?: string; evidenceVerified?: boolean }
  notes: string[]
}

export interface Roster {
  source?: 'COLLECTION'
  sheet: string
  headerRow: number
  fields: string[]
  nameField: string
  idField?: string
  people: { key: string; row: number; values: Record<string, string> }[]
  warnings: string[]
  fileId: string
  filename: string
}

export interface SpecProject {
  id: string
  name: string
  version: number
  noticeText?: string
  spec: MaterialSpec
  roster?: Roster
  ai: { configured: boolean; model: string; promptVersion: string }
  templates: {
    docx?: { fileId: string; filename?: string; placeholders?: string[]; unresolved?: string[]; error?: string }
    xlsx?: { fileId: string; filename?: string; headerRow?: number; detectedHeaderRow?: number; columns?: string[]; error?: string }
    knownFields: string[]
  }
  updatedAt: number
  warnings?: string[]
  /** naming tokens the roster cannot fill (present once a roster is imported) */
  namingUnknown?: string[]
}

export interface SpecProjectSummary {
  id: string
  name: string
  slots: number
  people: number
  files: number
  updatedAt: number
}

export type ItemStatus = 'UNMATCHED' | 'OK' | 'FIXABLE' | 'INVALID' | 'FIXING' | 'FIXED' | 'FIX_FAILED'

export interface SpecViolation {
  rule: string
  label: string
  expected: string
  actual: string
  fixable: boolean
  why?: string
}

export interface SpecCheck {
  status: 'OK' | 'FIXABLE' | 'INVALID'
  violations: SpecViolation[]
  facts: Record<string, any>
  fix?: { type: string; params: Record<string, any>; reason: string }
  afterFix?: SpecCheck
  fixError?: string
}

export interface SpecItem {
  id: string
  fileId: string
  relativePath: string
  filename?: string
  mediaType?: MediaKind
  sizeBytes?: number
  personKey?: string
  slotKey?: string
  matchMethod: 'AUTO' | 'MANUAL' | 'SUBMISSION' | 'AI' | 'NONE'
  matchNote?: string
  submittedBy?: string
  submittedAt?: number
  status: ItemStatus
  check?: SpecCheck
  fixedFileId?: string
  fixTaskId?: string
}

export interface SpecBoard {
  people: { key: string; name: string; row: number; complete: boolean; cells: Record<string, { count: number; itemId?: string; status: ItemStatus | 'MISSING' }> }[]
  summary: { people: number; complete: number; files: number; byStatus: Record<string, number> }
  items: SpecItem[]
  started?: number
  organization?: { matched: number; pending: number; model: string }
}

export interface CollectionLink {
  id: string
  active: boolean
  expiresAt: number
  createdAt: number
  files: number
  bytes: number
  maxFiles: number
  maxBytes: number
  path?: string
}

export interface PublicCollection {
  name: string
  expiresAt: number
  maxFileBytes: number
  slots: SpecSlot[]
}

export interface SpecDraft {
  spec: MaterialSpec
  errors: string[]
  warnings: string[]
  model: string
  promptVersion: string
  rounds: number
}


// ---------------- 学习播放器 (/api/v2/lessons) ----------------

export interface LessonNode {
  id: string
  parentId: string | null
  title: string
  summary: string
  start: number
  end: number
  keywords: string[]
}

export interface LessonSnippet {
  id: string
  title: string
  language: 'python' | 'javascript' | 'text'
  start: number
  end: number
  code: string
  explanation: string
  runnable: boolean
  /** share of the code lines OCR saw on screen near this time (0..1) */
  ocrSupport: number
  /** SCREEN = recognised from the video; SCREEN_COMPLETED = recognised + completed by AI; MODEL = written by AI from the narration */
  source: 'SCREEN' | 'SCREEN_COMPLETED' | 'MODEL'
  evidenceTime?: number
  evidenceFrame?: string
}

export interface LessonData {
  title: string
  summary: string
  duration: number
  nodes: LessonNode[]
  snippets: LessonSnippet[]
}

export interface LessonView {
  id: string
  videoFileId: string
  lesson: LessonData
  transcript: { begin: number; end: number; text: string }[]
  screen: { t: number; text: string }[]
  meta: { asrModel: string; llmModel: string; promptVersion: string; warnings: string[]; frames: number; ocrFrames: number }
  createdAt: number
}

export interface LessonSummary {
  id: string
  title: string
  videoFileId: string
  duration: number
  nodes: number
  snippets: number
  createdAt: number
}

export interface LessonCapabilities {
  asr: { configured: boolean; provider: string; model: string }
  ocr: boolean
  llm: boolean
  maxDurationMin: number
}
