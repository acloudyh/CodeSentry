export type SourceType = 'LOCAL' | 'GIT'
export type ScanMode = 'FULL' | 'INCREMENTAL'
export type WorkspaceMode = 'CURRENT' | 'BRANCH_SNAPSHOT'
export type ScanStatus = 'PENDING' | 'PREPARING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'

export interface ApiResponse<T> {
  success: boolean
  data: T
  message?: string
  timestamp: string
}

export interface ScanProject {
  id: number
  projectName: string
  sourceType: SourceType
  localPath?: string
  remoteUrl?: string
  managedPath?: string
  defaultBranch?: string
  enabled: boolean
  createdAt: string
}

export interface BranchView {
  name: string
  commit: string
  remote: boolean
}

export interface ScanRun {
  id: number
  projectId: number
  scanMode: ScanMode
  workspaceMode: WorkspaceMode
  scanRef?: string
  baselineRef?: string
  ruleProfile: string
  scanCommit?: string
  mergeBaseCommit?: string
  repositoryDirty: boolean
  status: ScanStatus
  totalFindings: number
  newFindings: number
  fixedFindings: number
  errorMessage?: string
  createdAt: string
  startedAt?: string
  finishedAt?: string
}

export interface ScanToolRun {
  id: number
  scanRunId: number
  toolName: string
  phase: string
  toolVersion?: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | 'CANCELLED'
  exitCode?: number
  commandLine?: string
  workingDirectory?: string
  rawReportPath?: string
  outputLog?: string
  errorMessage?: string
  startedAt: string
  finishedAt?: string
}

export interface ScanRunDetail {
  run: ScanRun
  toolRuns: ScanToolRun[]
  progressPercent: number
}

export interface FindingView {
  id: number
  instanceId?: number
  fingerprint: string
  scanner: string
  ruleId: string
  category?: string
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'
  title: string
  description?: string
  recommendation?: string
  cwe?: string
  scanRunId: number
  filePath: string
  startLine?: number
  endLine?: number
  codeSnippet?: string
  resultStatus: 'NEW' | 'EXISTING' | 'FIXED' | 'REOPENED'
  triageStatus?: string
  triageComment?: string
  reviewer?: string
  expiresAt?: string
}

export interface BatchTriageResult {
  requested: number
  matched: number
  unmatched: number
  matchedFindingIds: number[]
  unmatchedSelectors: string[]
}

export interface AiReviewRecord {
  id: number
  findingId: number
  scanRunId: number
  reviewJobId?: number
  reviewRound: 'PRIMARY' | 'VERIFY' | 'ERROR'
  provider: string
  model: string
  verdict: 'CONFIRMED' | 'FALSE_POSITIVE' | 'CONDITIONAL' | 'INSUFFICIENT_EVIDENCE' | 'AI_ERROR'
  confidence: number
  reason?: string
  evidenceLines?: string
  missingEvidence?: string
  recommendation?: string
  status: 'COMPLETED' | 'FAILED'
  errorMessage?: string
  createdAt: string
}

export interface AiReviewSummary {
  findingId: number
  scanRunId: number
  primaryReview?: AiReviewRecord
  verificationReview?: AiReviewRecord
  consolidatedVerdict: 'CONFIRMED' | 'FALSE_POSITIVE' | 'CONDITIONAL' | 'INSUFFICIENT_EVIDENCE' | 'NEEDS_HUMAN' | 'AI_ERROR'
  confidence: number
  confidenceAvailable: boolean
  disagreement: boolean
  highConfidence: boolean
  status: 'COMPLETED' | 'NEEDS_HUMAN' | 'FAILED' | 'CANCELLED'
  errorMessage?: string
}

export interface RemediationReportSummary {
  id: number
  projectId: number
  scanMode: ScanMode
  status: ScanStatus
  totalFindings: number
  newFindings: number
}

export interface AiReviewJob {
  id: number
  scanRunId: number
  provider: string
  model: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'
  totalFindings: number
  completedFindings: number
  failedFindings: number
  errorMessage?: string
  createdAt: string
  startedAt?: string
  finishedAt?: string
}

export interface AiReviewConfiguration {
  enabled: boolean
  provider: string
  model?: string
  baseUrl?: string
  apiKeyConfigured: boolean
}

export interface RuleProfile {
  id: string
  name: string
  description: string
  standards: string[]
  scanners: string[]
  rules: string[]
  offlineReady: boolean
}

export type TriageStatus = 'CONFIRMED' | 'FALSE_POSITIVE' | 'ACCEPTED_RISK' | 'FIXED_PENDING'

export interface DashboardSummary {
  projects: number
  scans: number
  runningScans: number
  newFindings: number
}
