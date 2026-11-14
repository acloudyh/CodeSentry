import type { ReportPackageJob, ReportPackageFormat, ReviewBudgetPolicy, ReviewPlan, GitCredentialProfile, ApplicationType, GitValidationResult, BusinessProject, ProjectScanRound, ProjectScanPayload, PageResult, LedgerFinding } from '@/types'
import { request } from './http'
import type { ReportAcceptance } from '@/types'
import type { AiReviewConfiguration, AiReviewJob, AiReviewRecord, AiReviewSummary, AiToolConfiguration, BatchTriageResult, BranchView, DashboardSummary, FindingView, RemediationReportSummary, RuleProfile, RuleLibraryStatus, ScanProject, ScanRun, ScanRunDetail, ScanMode, SourceType, TriageStatus, WorkspaceMode } from '@/types'

export interface CreateProjectPayload {
  applicationType?: ApplicationType
  targetNodeVersion?: string | null
  gitCredentialId?: number
  gitUsername?: string
  gitPassword?: string
  clearGitCredentials?: boolean
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

function displayProject(project: ScanProject): ScanProject {
  return { ...project, projectName: project.displayName ?? project.projectName }
}

export const api = {
  startReportPackage: (projectId:number,roundId:number,format:ReportPackageFormat,requestKey:string) => request<ReportPackageJob>(`/api/v1/business-projects/${projectId}/rounds/${roundId}/report-packages`, {method:'POST',body:JSON.stringify({format,requestKey})}),
  latestReportPackage: (projectId:number,roundId:number,format:ReportPackageFormat) => request<ReportPackageJob|null>(`/api/v1/business-projects/${projectId}/rounds/${roundId}/report-packages/latest?format=${format}`),
  reportPackage: (projectId:number,jobId:string) => request<ReportPackageJob>(`/api/v1/business-projects/${projectId}/report-packages/${jobId}`),
  reportPackageDownloadUrl: (projectId:number,jobId:string) => `/api/v1/business-projects/${projectId}/report-packages/${jobId}/download`,
  businessProjects: () => request<BusinessProject[]>('/api/v1/business-projects'),
  businessProject: (id: number) => request<BusinessProject>(`/api/v1/business-projects/${id}`),
  saveBusinessProject: (id: number | null, payload: { name: string; description: string }) => request<BusinessProject>(`/api/v1/business-projects${id === null ? '' : `/${id}`}`, { method: id === null ? 'POST' : 'PUT', body: JSON.stringify(payload) }),
  deleteBusinessProject: (id: number) => request<void>(`/api/v1/business-projects/${id}`, { method: 'DELETE' }),
  businessAssets: (id: number) => request<ScanProject[]>(`/api/v1/business-projects/${id}/assets`).then(rows => rows.map(displayProject)),
  attachAsset: (id: number, assetId: number) => request<void>(`/api/v1/business-projects/${id}/assets/${assetId}`, { method: 'PUT' }),
  detachAsset: (id: number, assetId: number) => request<void>(`/api/v1/business-projects/${id}/assets/${assetId}`, { method: 'DELETE' }),
  projectRounds: (id: number, page = 1, pageSize = 20) => request<PageResult<ProjectScanRound>>(`/api/v1/business-projects/${id}/rounds?page=${page}&pageSize=${pageSize}`),
  projectRound: (id: number, roundId: number) => request<ProjectScanRound>(`/api/v1/business-projects/${id}/rounds/${roundId}`),
  startProjectRound: (id: number, payload: ProjectScanPayload) => request<ProjectScanRound>(`/api/v1/business-projects/${id}/rounds`, { method: 'POST', body: JSON.stringify(payload) }),
  retryProjectRound: (id: number, roundId: number, requestKey: string) => request<ProjectScanRound>(`/api/v1/business-projects/${id}/rounds/${roundId}/retry`, { method: 'POST', body: JSON.stringify({ requestKey }) }),
  cancelProjectRound: (id: number, roundId: number) => request<ProjectScanRound>(`/api/v1/business-projects/${id}/rounds/${roundId}/cancel`, { method: 'POST' }),
  projectFindings: (id: number, params: URLSearchParams, signal?: AbortSignal) => request<PageResult<LedgerFinding> & { pendingReviewCount?: number; includedCount?: number; classificationCounts?: { quality: number; security: number; audit: number }; summary?: { total: number; newCount: number; fixedCount: number } }>(`/api/v1/business-projects/${id}/findings?${params}`, { signal }),
  projectReportUrl: (id: number, roundId: number, format: 'html' | 'docx' | 'pdf') => `/api/v1/business-projects/${id}/rounds/${roundId}/report/${format}`,

  dashboard: () => request<DashboardSummary>('/api/v1/dashboard/summary'),
  projects: () => request<ScanProject[]>('/api/v1/projects').then(projects => projects.map(displayProject)),
  createProject: (payload: CreateProjectPayload) => request<ScanProject>('/api/v1/projects', {
    method: 'POST',
    body: JSON.stringify(payload),
  }).then(displayProject),
  updateJarProject: (id: number, projectName: string, javaVersion: number | null, file: File | null) => {
    const body = new FormData()
    body.append('projectName', projectName)
    if (javaVersion !== null) body.append('javaVersion', String(javaVersion))
    if (file) body.append('file', file)
    return request<ScanProject>(`/api/v1/projects/${id}/jar`, { method: 'PUT', body }).then(displayProject)
  },
  deleteProject: (id: number) => request<void>(`/api/v1/projects/${id}`, { method: 'DELETE' }),
  updateProject: (id: number, payload: CreateProjectPayload) => request<ScanProject>(`/api/v1/projects/${id}`, {
    method: 'PUT', body: JSON.stringify(payload),
  }).then(displayProject),
  gitCredentials: () => request<GitCredentialProfile[]>('/api/v1/settings/git-credentials'),
  saveGitCredential: (id: number | null, payload: { name: string; serverUrl: string; username: string; password?: string }) => request<GitCredentialProfile>(`/api/v1/settings/git-credentials${id === null ? '' : `/${id}`}`, { method: id === null ? 'POST' : 'PUT', body: JSON.stringify(payload) }),
  deleteGitCredential: (id: number) => request<void>(`/api/v1/settings/git-credentials/${id}`, { method: 'DELETE' }),
  validateGit: (payload: { remoteUrl: string; gitCredentialId?: number; gitUsername?: string; gitPassword?: string; projectId?: number; useStoredCredentials?: boolean }) => request<GitValidationResult>('/api/v1/projects/git/validate', { method: 'POST', body: JSON.stringify(payload) }),
  syncProject: (id: number) => request<ScanProject>(`/api/v1/projects/${id}/sync`, { method: 'POST' }).then(displayProject),
  branches: (id: number, refresh = false) => request<BranchView[]>(`/api/v1/projects/${id}/branches?refresh=${refresh}`),
  scanRuns: (limit = 100) => request<ScanRun[]>(`/api/v1/scan-runs?limit=${limit}`),
  scanRunDetail: (id: number) => request<ScanRunDetail>(`/api/v1/scan-runs/${id}/detail`),
  cancelScan: (id: number) => request<ScanRun>(`/api/v1/scan-runs/${id}/cancel`, { method: 'POST' }),
  createJarProject: (file: File, projectName: string, javaVersion: number | null) => {
    const body = new FormData()
    body.append('file', file)
    if (projectName.trim()) body.append('projectName', projectName.trim())
    if (javaVersion !== null) body.append('javaVersion', String(javaVersion))
    return request<ScanProject>('/api/v1/projects/jar', { method: 'POST', body }).then(displayProject)
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
  findings: (limit = 200, issueType = 'ALL') => request<FindingView[]>(`/api/v1/findings?limit=${limit}&issueType=${issueType}`),
  runFindings: (runId: number) => request<FindingView[]>(`/api/v1/findings/scan-runs/${runId}`),
  reportExclusions: (runId: number) => request<{ findingId: number; reason: string }[]>(`/api/v1/reports/scan-runs/${runId}/excluded-findings`),
  reportIncludedFindingIds: (runId: number) => request<number[]>(`/api/v1/reports/scan-runs/${runId}/included-finding-ids`),
  triageFinding: (findingId: number, payload: TriageFindingPayload) => request<FindingView>(`/api/v1/findings/${findingId}/triage`, {
    method: 'PUT', body: JSON.stringify(payload),
  }),
  batchTriageFindings: (runId: number, payload: BatchTriageFindingPayload) => request<BatchTriageResult>(`/api/v1/findings/scan-runs/${runId}/triage/batch`, {
    method: 'POST', body: JSON.stringify(payload),
  }),
  reviewBudgetPolicy: () => request<ReviewBudgetPolicy>('/api/v1/ai-reviews/budget-policy'),
  saveReviewBudgetPolicy: (payload: ReviewBudgetPolicy) => request<ReviewBudgetPolicy>('/api/v1/ai-reviews/budget-policy', { method: 'PUT', body: JSON.stringify(payload) }),
  previewReview: (runId: number, options: {findingId?: number;failedOnly?: boolean;includeDependencies?: boolean;maxRequests?: number;maxInputTokens?: number;maxOutputTokens?: number;maxCost?: number}) => request<ReviewPlan>(`/api/v1/ai-reviews/scan-runs/${runId}/preview`, { method: 'POST', body: JSON.stringify(options) }),
  startReviewPlan: (planId: string, allowPaid: boolean) => request<AiReviewJob>(`/api/v1/ai-reviews/plans/${planId}/start`, { method:'POST', body:JSON.stringify({allowPaid}) }),
  reviewBudgetUsage: (jobId: number) => request<Record<string, unknown>>(`/api/v1/ai-reviews/jobs/${jobId}/budget`),
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
  verifyReportOnline: (runId: number) => request<ScanRun>(`/api/v1/reports/scan-runs/${runId}/verify-online`, { method: 'POST' }),
  reportPage: (page = 1, pageSize = 20, businessProjectId?: number, assetId?: number) => request<PageResult<RemediationReportSummary>>(`/api/v1/reports/page?${new URLSearchParams({ page: String(page), pageSize: String(pageSize), ...(businessProjectId ? { businessProjectId: String(businessProjectId) } : {}), ...(assetId ? { assetId: String(assetId) } : {}) })}`),
  reports: (limit = 100, businessProjectId?: number, assetId?: number) => request<RemediationReportSummary[]>(`/api/v1/reports?${new URLSearchParams({ limit: String(limit), ...(businessProjectId ? { businessProjectId: String(businessProjectId) } : {}), ...(assetId ? { assetId: String(assetId) } : {}) })}`),
  ruleProfiles: () => request<RuleProfile[]>('/api/v1/rule-profiles'),
  ruleLibraryStatus: () => request<RuleLibraryStatus>('/api/v1/rule-profiles/library'),
  updateRuleLibrary: () => request<RuleLibraryStatus>('/api/v1/rule-profiles/library/update', { method: 'POST' }),
  reportUrl: (runId: number, format: 'html' | 'docx' | 'pdf') => `/api/v1/reports/scan-runs/${runId}/${format}`,
}
