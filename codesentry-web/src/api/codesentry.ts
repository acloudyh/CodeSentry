import { request } from './http'
import type { AiReviewConfiguration, AiReviewJob, AiReviewRecord, AiReviewSummary, BranchView, DashboardSummary, FindingView, RuleProfile, ScanProject, ScanRun, ScanRunDetail, ScanMode, SourceType, TriageStatus, WorkspaceMode } from '@/types'

export interface CreateProjectPayload {
  projectName: string
  sourceType: SourceType
  localPath?: string
  remoteUrl?: string
  defaultBranch?: string
}

export interface CreateScanPayload {
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

export const api = {
  dashboard: () => request<DashboardSummary>('/api/v1/dashboard/summary'),
  projects: () => request<ScanProject[]>('/api/v1/projects'),
  createProject: (payload: CreateProjectPayload) => request<ScanProject>('/api/v1/projects', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  deleteProject: (id: number) => request<void>(`/api/v1/projects/${id}`, { method: 'DELETE' }),
  branches: (id: number, refresh = false) => request<BranchView[]>(`/api/v1/projects/${id}/branches?refresh=${refresh}`),
  scanRuns: (limit = 100) => request<ScanRun[]>(`/api/v1/scan-runs?limit=${limit}`),
  scanRunDetail: (id: number) => request<ScanRunDetail>(`/api/v1/scan-runs/${id}/detail`),
  startScan: (payload: CreateScanPayload) => request<ScanRun>('/api/v1/scan-runs', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),
  findings: (limit = 200) => request<FindingView[]>(`/api/v1/findings?limit=${limit}`),
  runFindings: (runId: number) => request<FindingView[]>(`/api/v1/findings/scan-runs/${runId}`),
  triageFinding: (findingId: number, payload: TriageFindingPayload) => request<FindingView>(`/api/v1/findings/${findingId}/triage`, {
    method: 'PUT', body: JSON.stringify(payload),
  }),
  aiReviewConfig: () => request<AiReviewConfiguration>('/api/v1/ai-reviews/config'),
  aiReviewFinding: (findingId: number) => request<AiReviewSummary>(`/api/v1/ai-reviews/findings/${findingId}`, { method: 'POST' }),
  aiFindingReview: (findingId: number) => request<AiReviewSummary | null>(`/api/v1/ai-reviews/findings/${findingId}`),
  aiReviewRun: (runId: number) => request<AiReviewJob>(`/api/v1/ai-reviews/scan-runs/${runId}`, { method: 'POST' }),
  aiRunReviews: (runId: number) => request<AiReviewSummary[]>(`/api/v1/ai-reviews/scan-runs/${runId}`),
  aiReviewJob: (jobId: number) => request<AiReviewJob>(`/api/v1/ai-reviews/jobs/${jobId}`),
  aiReviewJobRecords: (jobId: number) => request<AiReviewRecord[]>(`/api/v1/ai-reviews/jobs/${jobId}/records`),
  latestAiReviewJob: (runId: number) => request<AiReviewJob | null>(`/api/v1/ai-reviews/scan-runs/${runId}/jobs/latest`),
  ruleProfiles: () => request<RuleProfile[]>('/api/v1/rule-profiles'),
  reportUrl: (runId: number, format: 'html' | 'docx' | 'pdf') => `/api/v1/reports/scan-runs/${runId}/${format}`,
}
