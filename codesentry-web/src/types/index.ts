export type ApplicationType = 'FRONTEND' | 'BACKEND' | 'FULL_STACK'
export type SourceType = 'LOCAL' | 'GIT' | 'JAR_UPLOAD'
export type ScanMode = 'FULL' | 'INCREMENTAL'
export type WorkspaceMode = 'CURRENT' | 'BRANCH_SNAPSHOT' | 'JAR_UPLOAD'
export type ScanStatus =
  'PENDING' | 'PREPARING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'

export interface ApiResponse<T> {
  success: boolean
  data: T
  message?: string
  timestamp: string
}

export interface ScanProject {
  applicationType?: ApplicationType
  targetNodeVersion?: string | null
  gitCredentialId?: number
  gitUsername?: string
  gitCredentialConfigured?: boolean
  lastSyncedAt?: string
  lastSyncStatus?: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  lastSyncMessage?: string
  businessProjectId?: number
  displayName?: string
  targetJavaVersion?: number | null
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
  networkMode?: 'LOCAL' | 'ONLINE'
  remediationStatus?: string
  remediationMessage?: string
  sourceReportRunId?: number
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
  issueType?: 'QUALITY' | 'SECURITY' | 'AUDIT'
  precheck?: { route: string; reason: string; recommendation: string; policyVersion: string }

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
  consolidatedVerdict:
    | 'CONFIRMED'
    | 'FALSE_POSITIVE'
    | 'CONDITIONAL'
    | 'INSUFFICIENT_EVIDENCE'
    | 'NEEDS_HUMAN'
    | 'AI_ERROR'
  confidence: number
  confidenceAvailable: boolean
  disagreement: boolean
  highConfidence: boolean
  status: 'COMPLETED' | 'NEEDS_HUMAN' | 'FAILED' | 'CANCELLED'
  errorMessage?: string
}

export interface RemediationReportSummary {
  qualityFindings?: number
  legacyQualityInReport?: number
  networkMode?: 'LOCAL' | 'ONLINE'
  remediationStatus?: string
  remediationMessage?: string
  ledgerFindings: number
  detailGroups: number
  notIncludedFindings: number
  id: number
  projectId: number
  scanMode: ScanMode
  status: ScanStatus
  totalFindings: number
  newFindings: number
  createdAt: string
}

export interface AiReviewJob {
  concurrency?: number
  id: number
  scanRunId: number
  provider: string
  model: string
  status:
    'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED' | 'BUDGET_EXHAUSTED'
  totalFindings: number
  completedFindings: number
  failedFindings: number
  errorMessage?: string
  createdAt: string
  startedAt?: string
  finishedAt?: string
}

export interface AiReviewConfiguration {
  maxConcurrency?: number
  enabled: boolean
  provider: string
  model?: string
  baseUrl?: string
  apiKeyConfigured: boolean
}

export interface AiToolConfiguration {
  id: number
  name: string
  provider: string
  baseUrl: string
  model: string
  apiKeyConfigured: boolean
  enabled: boolean
  defaultTool: boolean
  createdAt: string
  updatedAt: string
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
  securityFindings?: number
  qualityFindings?: number
  businessProjects: number
  assets: number
  codeAssets: number
  jarAssets: number
  unassignedAssets: number
  projects: number
  scans: number
  runningScans: number
  newFindings: number
}

export interface ReportAcceptance {
  source: ScanRun
  verification?: ScanRun
  history: ScanRun[]
  counts: Record<string, number>
  items: Array<{
    finding: FindingView
    status: string
    triageStatus?: string
    comment?: string
    reviewer?: string
  }>
}

export interface RuleLibraryStatus {
  state: string
  available: boolean
  version: string | null
  updatedAt: string | null
  ruleCount: number | null
  message: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  pageSize: number
}
export interface BusinessProject {
  id: number
  name: string
  description?: string
  repositoryCount: number
  jarCount: number
  latestRound?: ProjectScanRound
}
export interface ProjectScanItem {
  id: number
  assetId: number
  assetName: string
  sourceType: SourceType
  workspaceMode: WorkspaceMode
  scanRef?: string
  runId?: number
  status: ScanStatus
  errorMessage?: string
  run?: ScanRun
}
export interface ProjectScanRound {
  assetHistory?: boolean
  unselectedAssets?: Array<{ assetId: number; assetName: string }>
  id: number
  businessProjectId: number
  projectName: string
  fullScope: boolean
  assetCount: number
  networkMode: 'LOCAL' | 'ONLINE'
  ruleProfile: string
  retryOf?: number
  createdAt: string
  status: ScanStatus
  completedCount: number
  failedCount: number
  items: ProjectScanItem[]
}
export interface ProjectScanPayload {
  requestKey: string
  refreshRemote?: boolean
  networkMode: 'LOCAL' | 'ONLINE'
  ruleProfile: string
  fullScope: boolean
  assets: Array<{ assetId: number; workspaceMode: WorkspaceMode; scanRef?: string }>
}
export interface LedgerFinding extends FindingView {
  assetId: number
  assetName: string
  reportState: 'INCLUDED' | 'EXCLUDED' | 'PENDING'
}

export interface GitValidationResult {
  valid: boolean
  code: string
  message: string
  defaultBranch?: string
  branches: string[]
}

export interface GitCredentialProfile {
  id: number
  name: string
  serverUrl: string
  username: string
  usageCount: number
  updatedAt: string
}

export interface ReviewBudgetPolicy {
  paidEnabled: boolean
  pricedModelSignature?: string
  pricedModel?: string
  pricedProvider?: string
  inputPricePerMillion: number
  outputPricePerMillion: number
  maxCost: number
  dailyCostLimit: number
  maxRequests: number
  maxInputTokens: number
  maxOutputTokens: number
  outputTokensPerRequest: number
}
export interface ReviewPlan {
  id: string
  scanRunId: number
  jobId?: number
  model: string
  provider: string
  budget: ReviewBudgetPolicy
  total: number
  quality: number
  security: number
  audit: number
  localPrecheck: number
  alreadyReviewed: number
  reusable: number
  modelCandidates: number
  estimatedRequests: number
  estimatedInputTokens: number
  estimatedOutputTokens: number
  estimatedCost: number
  expiresAt: string
}

export type ReportPackageFormat = 'PDF' | 'WORD' | 'BOTH'
export interface ReportPackageJob {
  id: string
  businessProjectId: number
  roundId: number
  format: ReportPackageFormat
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  fileName: string
  totalFiles: number
  completedFiles: number
  fileBytes: number
  errorMessage?: string
  createdAt: string
  finishedAt?: string
}
