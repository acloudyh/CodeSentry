import { request } from './http'
import type { ReportAcceptance } from '@/types'
import type { AiReviewConfiguration, AiReviewJob, AiReviewRecord, AiReviewSummary, AiToolConfiguration, BatchTriageResult, BranchView, DashboardSummary, FindingView, RemediationReportSummary, RuleProfile, RuleLibraryStatus, ScanProject, ScanRun, ScanRunDetail, ScanMode, SourceType, TriageStatus, WorkspaceMode } from '@/types'

export interface CreateProjectPayload {
  targetJavaVersion?: number | null
  projectName: string
  sourceType: SourceType
  localPath?: string
  remoteUrl?: string
  defaultBranch?: string
}

export interface CreateScanPayload {
  networkMode?: 'LOCAL' | 'ONLINE'
  sourceReportRunId?: number
  projectId: number
  scanMode: ScanMode
  workspaceMode: WorkspaceMode
  scanRef?: string
  baselineRef?: string
  refreshRemote: boolean
  ruleProfile: string
}

export interface TriageFindingPayload {
  triageStatus: TriageStatus
  comment?: string
  reviewer: string
  expiresAt?: string
}

export interface BatchTriageFindingPayload extends TriageFindingPayload {
  selectors: Array<{ findingId: number }>
}

export interface AiReviewConfigurationPayload {
  enabled: boolean
  baseUrl?: string
  model?: string
  apiKey?: string
}

export interface AiToolConfigurationPayload {
  name: string
  provider: string
  baseUrl: string
  model: string
  apiKey?: string
  enabled: boolean
  defaultTool: boolean
}

export const api = {
  dashboard: () => request<DashboardSummary>('/api/v1/dashboard/summary'),
  projects: () => request<ScanProject[]>('/api/v1/projects'),
  createProject: (payload: CreateProjectPayload) => request<ScanProject>('/api/v1/projects', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  updateJarProject: (id: number, projectName: string, javaVersion: number | null, file: File | null) => {
    const body = new FormData()
    body.append('projectName', projectName)
    if (javaVersion !== null) body.append('javaVersion', String(javaVersion))
    if (file) body.append('file', file)
    return request<ScanProject>(`/api/v1/projects/${id}/jar`, { method: 'PUT', body })
  },
  deleteProject: (id: number) => request<void>(`/api/v1/projects/${id}`, { method: 'DELETE' }),
  updateProject: (id: number, payload: CreateProjectPayload) => request<ScanProject>(`/api/v1/projects/${id}`, {
    method: 'PUT', body: JSON.stringify(payload),
  }),
  branches: (id: number, refresh = false) => request<BranchView[]>(`/api/v1/projects/${id}/branches?refresh=${refresh}`),
  scanRuns: (limit = 100) => request<ScanRun[]>(`/api/v1/scan-runs?limit=${limit}`),
  scanRunDetail: (id: number) => request<ScanRunDetail>(`/api/v1/scan-runs/${id}/detail`),
  cancelScan: (id: number) => request<ScanRun>(`/api/v1/scan-runs/${id}/cancel`, { method: 'POST' }),
  createJarProject: (file: File, projectName: string, javaVersion: number | null) => {
    const body = new FormData()
    body.append('file', file)
    if (projectName.trim()) body.append('projectName', projectName.trim())
    if (javaVersion !== null) body.append('javaVersion', String(javaVersion))
    return request<ScanProject>('/api/v1/projects/jar', { method: 'POST', body })
  },
  uploadJar: (file: File, projectName: string, javaVersion: number | null) => {
    const body = new FormData()
    body.append('file', file)
    if (projectName.trim()) body.append('projectName', projectName.trim())
    if (javaVersion !== null) body.append('javaVersion', String(javaVersion))
    return request<ScanRun>('/api/v1/scan-runs/jar', { method: 'POST', body })
  },
  startScan: (payload: CreateScanPayload) => request<ScanRun>('/api/v1/scan-runs', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  findings: (limit = 200) => request<FindingView[]>(`/api/v1/findings?limit=${limit}`),
  runFindings: (runId: number) => request<FindingView[]>(`/api/v1/findings/scan-runs/${runId}`),
  reportIncludedFindingIds: (runId: number) => request<number[]>(`/api/v1/reports/scan-runs/${runId}/included-finding-ids`),
  triageFinding: (findingId: number, payload: TriageFindingPayload) => request<FindingView>(`/api/v1/findings/${findingId}/triage`, {
    method: 'PUT', body: JSON.stringify(payload),
  }),
  batchTriageFindings: (runId: number, payload: BatchTriageFindingPayload) => request<BatchTriageResult>(`/api/v1/findings/scan-runs/${runId}/triage/batch`, {
    method: 'POST', body: JSON.stringify(payload),
  }),
  aiReviewConfig: () => request<AiReviewConfiguration>('/api/v1/ai-reviews/config'),
  updateAiReviewConfig: (payload: AiReviewConfigurationPayload) => request<AiReviewConfiguration>('/api/v1/ai-reviews/config', {
    method: 'PUT', body: JSON.stringify(payload),
  }),
  aiReviewFinding: (findingId: number) => request<AiReviewSummary>(`/api/v1/ai-reviews/findings/${findingId}`, { method: 'POST' }),
  aiFindingReview: (findingId: number) => request<AiReviewSummary | null>(`/api/v1/ai-reviews/findings/${findingId}`),
  aiReviewRun: (runId: number) => request<AiReviewJob>(`/api/v1/ai-reviews/scan-runs/${runId}`, { method: 'POST' }),
  aiRetryFailedRun: (runId: number) => request<AiReviewJob>(`/api/v1/ai-reviews/scan-runs/${runId}/retry-failed`, { method: 'POST' }),
  aiRunReviews: (runId: number) => request<AiReviewSummary[]>(`/api/v1/ai-reviews/scan-runs/${runId}`),
  aiReviewJob: (jobId: number) => request<AiReviewJob>(`/api/v1/ai-reviews/jobs/${jobId}`),
  cancelAiReview: (jobId: number) => request<AiReviewJob>(`/api/v1/ai-reviews/jobs/${jobId}/cancel`, { method: 'POST' }),
  aiReviewJobRecords: (jobId: number) => request<AiReviewRecord[]>(`/api/v1/ai-reviews/jobs/${jobId}/records`),
  aiReviewJobFindings: (jobId: number) => request<FindingView[]>(`/api/v1/ai-reviews/jobs/${jobId}/findings`),
  latestAiReviewJob: (runId: number) => request<AiReviewJob | null>(`/api/v1/ai-reviews/scan-runs/${runId}/jobs/latest`),
  aiTools: () => request<AiToolConfiguration[]>('/api/v1/ai-tools'),
  createAiTool: (payload: AiToolConfigurationPayload) => request<AiToolConfiguration>('/api/v1/ai-tools', {
    method: 'POST', body: JSON.stringify(payload),
  }),
  updateAiTool: (id: number, payload: AiToolConfigurationPayload) => request<AiToolConfiguration>(`/api/v1/ai-tools/${id}`, {
    method: 'PUT', body: JSON.stringify(payload),
  }),
  deleteAiTool: (id: number) => request<void>(`/api/v1/ai-tools/${id}`, { method: 'DELETE' }),
  reportAcceptance: (id: number, verificationId?: number) => request<ReportAcceptance>(`/api/v1/reports/scan-runs/${id}/acceptance${verificationId ? `?verificationId=${verificationId}` : ''}`),
  reports: (limit = 100) => request<RemediationReportSummary[]>(`/api/v1/reports?limit=${limit}`),
  ruleProfiles: () => request<RuleProfile[]>('/api/v1/rule-profiles'),
  ruleLibraryStatus: () => request<RuleLibraryStatus>('/api/v1/rule-profiles/library'),
  updateRuleLibrary: () => request<RuleLibraryStatus>('/api/v1/rule-profiles/library/update', { method: 'POST' }),
  reportUrl: (runId: number, format: 'html' | 'docx' | 'pdf') => `/api/v1/reports/scan-runs/${runId}/${format}`,
}
