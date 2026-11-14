<script setup lang="ts">
import { useId } from 'vue'
const formFieldId = useId()
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AiReviewPlanner from '@/components/AiReviewPlanner.vue'
import ProjectLedger from '@/components/ProjectLedger.vue'
import type { BusinessProject } from '@/types'
import { api } from '@/api/codesentry'
import { categoryLabel, findingTitle, scannerLabel, statusLabel } from '@/utils/labels'
import { formatChinaTime } from '@/utils/datetime'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import type {
  AiReviewConfiguration,
  AiReviewJob,
  AiReviewRecord,
  AiReviewSummary,
  FindingView,
  ScanProject,
  ScanRun,
  TriageStatus,
} from '@/types'

const router = useRouter()
const businessProjectsLoading = ref(true)
const assetDetailsOpen = ref(false)
let initialized = false
let assetGeneration = 0
const businessProjects = ref<BusinessProject[]>([])
const businessProjectId = ref<number | null>(null)
const findings = ref<FindingView[]>([])
const includedFindingIds = ref<number[]>([])
const exclusionReasons = ref<Record<number, string>>({})
const reviewedIssueCount = computed(
  () =>
    findings.value.filter((item) =>
      ['COMPLETED', 'NEEDS_HUMAN'].includes(aiReviews.value[item.id]?.status ?? ''),
    ).length,
)
const route = useRoute()
const runs = ref<ScanRun[]>([])
const projects = ref<ScanProject[]>([])
const selectedProjectId = ref<number | null>(null)
const selectedRunId = ref<number | null>(null)
const severity = ref('ALL')
const state = ref('ALL')
const reportState = ref('ALL')
const keyword = ref('')
const error = ref('')
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const pageSizes = [20, 50, 100]
const selected = ref<FindingView | null>(null)
const snippetFinding = ref<FindingView | null>(null)
const saving = ref(false)
const selectedFindingIds = ref<number[]>([])
const batchReviewOpen = ref(false)
const batchReviewSaving = ref(false)
const batchReviewResult = ref('')
const reviewPlannerOpen = ref(false),
  reviewPlannerFinding = ref<number>(),
  reviewPlannerFailed = ref(false)
const reviewPlannerRuns = computed(() =>
  selectedRunId.value
    ? [
        {
          runId: selectedRunId.value,
          assetName: selectedProject.value?.projectName ?? `扫描 #${selectedRunId.value}`,
        },
      ]
    : [],
)
function plannedReviews(jobs: AiReviewJob[]) {
  if (jobs[0]) {
    aiJob.value = jobs[0]
    pollAiJob(jobs[0].id)
  }
}
const aiConfig = ref<AiReviewConfiguration>({
  enabled: false,
  provider: 'OPENAI_COMPATIBLE',
  apiKeyConfigured: false,
})
const aiReviews = ref<Record<number, AiReviewSummary>>({})
const aiReviewingId = ref<number | null>(null)
const aiJob = ref<AiReviewJob | null>(null)
const startingAiBatch = ref(false)
const stoppingAi = ref(false)
async function stopAiReview() {
  if (!aiJob.value || !window.confirm('确认停止工具审核？已完成的审核保留，剩余问题不再执行。'))
    return
  stoppingAi.value = true
  try {
    aiJob.value = await api.cancelAiReview(aiJob.value.id)
    if (aiPollTimer) window.clearTimeout(aiPollTimer)
    await loadAiReviews(aiJob.value.scanRunId)
    await loadReportStatus(aiJob.value.scanRunId)
    if (aiDetailsOpen.value) await loadAiJobRecords(aiJob.value!.id)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '停止工具审核失败'
  } finally {
    stoppingAi.value = false
  }
}
const aiDetailsOpen = ref(false)
const aiDetailsLoading = ref(false)
const aiDetailError = ref('')
const aiJobRecords = ref<AiReviewRecord[]>([])
const aiExecutionFindings = ref<FindingView[]>([])
let aiPollTimer: number | undefined
const review = reactive({
  triageStatus: 'CONFIRMED' as TriageStatus,
  comment: '',
  reviewer: '本地复核人',
  expiresAt: '',
})
const batchReview = reactive({
  triageStatus: 'CONFIRMED' as TriageStatus,
  comment: '',
  reviewer: '本地复核人',
  expiresAt: '',
})

const isRedacted = (item: FindingView) => item.codeSnippet?.trim() === '[REDACTED]'
const isDependencyVulnerability = (item: FindingView) =>
  item.scanner === 'Trivy' && item.category === 'VULNERABILITY'
const findingLocation = (item: FindingView) =>
  isDependencyVulnerability(item) ? item.filePath : `${item.filePath}:${item.startLine ?? '-'}`
const findingLocationLabel = (item: FindingView) =>
  isDependencyVulnerability(item) ? '依赖文件' : '代码位置'
const projectRuns = computed(() =>
  selectedProjectId.value === null
    ? runs.value
    : runs.value.filter((item) => item.projectId === selectedProjectId.value),
)
const scopedProjects = computed(() =>
  projects.value.filter(
    (p) =>
      p.businessProjectId === businessProjectId.value ||
      (assetDetailsOpen.value && !p.businessProjectId && p.id === selectedProjectId.value),
  ),
)
function chooseBusinessProject(requested?: unknown) {
  const candidates = [
    Number(requested),
    Number(localStorage.getItem('codesentry-ledger-business-project')),
  ]
  return (
    candidates.find((id) => businessProjects.value.some((p) => p.id === id)) ??
    businessProjects.value[0]?.id ??
    null
  )
}
async function changeBusinessProject() {
  assetGeneration++
  assetDetailsOpen.value = false
  selectedRunId.value = null
  selected.value = null
  snippetFinding.value = null
  batchReviewOpen.value = false
  aiDetailsOpen.value = false
  await router.replace({
    path: '/findings',
    query: businessProjectId.value ? { businessProjectId: businessProjectId.value } : {},
  })
}
async function backToProjectLedger() {
  await changeBusinessProject()
}
watch(businessProjectId, (id) => {
  if (id) localStorage.setItem('codesentry-ledger-business-project', String(id))
})
const selectedProject = computed(() =>
  projects.value.find((item) => item.id === selectedProjectId.value),
)
async function openAssetRun(runId: number) {
  const generation = ++assetGeneration
  try {
    let run = runs.value.find((item) => item.id === runId)
    if (!run) {
      run = (await api.scanRunDetail(runId)).run
      if (generation !== assetGeneration) return
      runs.value.unshift(run)
    }
    const asset = projects.value.find((p) => p.id === run!.projectId)
    if (!asset) throw new Error('扫描资产不存在或已移除')
    if (generation !== assetGeneration) return
    businessProjectId.value =
      asset.businessProjectId &&
      businessProjects.value.some((p) => p.id === asset.businessProjectId)
        ? asset.businessProjectId
        : chooseBusinessProject()
    assetDetailsOpen.value = true
    selectedProjectId.value = run.projectId
    selectedRunId.value = run.id
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '扫描批次加载失败'
  }
}
watch(
  () => route.fullPath,
  () => {
    if (!initialized) return
    const id = Number(route.query.runId)
    if (id > 0) void openAssetRun(id)
    else {
      assetDetailsOpen.value = false
      selectedRunId.value = null
      selected.value = null
      snippetFinding.value = null
      businessProjectId.value = chooseBusinessProject(route.query.businessProjectId)
    }
  },
)
const selectedRun = computed(() => runs.value.find((item) => item.id === selectedRunId.value))
const batchNewCount = computed(
  () =>
    findings.value.filter((item) => item.resultStatus === 'NEW' || item.resultStatus === 'REOPENED')
      .length,
)
const batchFixedCount = computed(
  () => findings.value.filter((item) => item.resultStatus === 'FIXED').length,
)
const includedIdSet = computed(() => new Set(includedFindingIds.value))
const reportStatus = (item: FindingView) =>
  includedIdSet.value.has(item.id)
    ? '已采纳'
    : item.triageStatus || aiReviews.value[item.id]
      ? '未采纳'
      : '待审核'
async function loadReportStatus(runId: number) {
  const [ids, exclusions] = await Promise.all([
    api.reportIncludedFindingIds(runId),
    api.reportExclusions(runId),
  ])
  includedFindingIds.value = ids
  exclusionReasons.value = Object.fromEntries(
    exclusions.map((item) => [item.findingId, item.reason]),
  )
}
const batchNumber = (id: number) => `#${String(id).padStart(5, '0')}`
const formatTime = formatChinaTime
const modeLabel = (run: ScanRun) => (run.scanMode === 'FULL' ? '全量扫描' : '增量扫描')
const runOptionLabel = (run: ScanRun) =>
  `${batchNumber(run.id)} · ${modeLabel(run)} · ${formatTime(run.createdAt)}`

function openReview(item: FindingView) {
  selected.value = item
  review.triageStatus = (item.triageStatus as TriageStatus) ?? 'CONFIRMED'
  review.comment = item.triageComment ?? ''
  review.reviewer = item.reviewer ?? localStorage.getItem('codesentry-reviewer') ?? '本地复核人'
  review.expiresAt = item.expiresAt?.slice(0, 16) ?? ''
}

const aiVerdictLabel = (value?: string) =>
  ({
    CONFIRMED: '确认风险',
    FALSE_POSITIVE: '疑似误报',
    CONDITIONAL: '条件风险',
    INSUFFICIENT_EVIDENCE: '证据不足',
    NEEDS_HUMAN: '工具结论待确认',
    AI_ERROR: '审核失败',
    CANCELLED: '审核已停止',
  })[value ?? ''] ?? '尚未审核'
const aiConfidence = (value?: number) =>
  value == null ? '-' : `${Number((value * 100).toFixed(2))}%`
const aiJobStatusLabel = (value?: AiReviewJob['status']) =>
  (
    ({
      PENDING: '等待执行',
      RUNNING: '正在审核',
      COMPLETED: '审核完成',
      PARTIAL: '部分完成',
      FAILED: '审核失败',
      CANCELLED: '已停止',
    }) as Record<string, string>
  )[value ?? ''] ?? '未知状态'
const aiExecutionRows = computed(() =>
  aiExecutionFindings.value.map((finding) => {
    const records = aiJobRecords.value
      .filter((record) => record.findingId === finding.id)
      .sort((a, b) => b.id - a.id)
    const retained =
      !records.length && aiReviews.value[finding.id]?.status !== 'FAILED'
        ? aiReviews.value[finding.id]
        : undefined
    const primary =
      records.find((record) => record.reviewRound === 'PRIMARY') ?? retained?.primaryReview
    const verification =
      records.find(
        (record) => record.reviewRound === 'VERIFY' && (!primary || record.id > primary.id),
      ) ?? retained?.verificationReview
    const failure = records[0]?.reviewRound === 'ERROR' ? records[0] : undefined
    const stage = failure
      ? 'FAILED'
      : verification
        ? 'COMPLETED'
        : aiJob.value?.status === 'CANCELLED'
          ? 'CANCELLED'
          : primary
            ? 'VERIFYING'
            : 'PENDING'
    return { finding, primary, verification, failure, stage }
  }),
)
const aiStageLabel = (value: string) =>
  ({
    PENDING: '等待初审',
    VERIFYING: '复审中',
    COMPLETED: '复核完成',
    FAILED: '执行失败',
    CANCELLED: '已停止',
  })[value] ?? value

async function loadAiJobRecords(jobId: number) {
  aiDetailsLoading.value = true
  aiDetailError.value = ''
  try {
    const [records, sourceFindings] = await Promise.all([
      api.aiReviewJobRecords(jobId),
      api.aiReviewJobFindings(jobId),
    ])
    aiJobRecords.value = records
    aiExecutionFindings.value = sourceFindings
  } catch (reason) {
    aiDetailError.value = reason instanceof Error ? reason.message : '工具执行详情加载失败'
  } finally {
    aiDetailsLoading.value = false
  }
}

async function openAiDetails() {
  if (!aiJob.value) return
  aiDetailsOpen.value = true
  await loadAiJobRecords(aiJob.value.id)
}

async function loadAiReviews(runId: number) {
  if (!aiConfig.value.enabled) {
    aiReviews.value = {}
    aiJob.value = null
    return
  }
  const [reviews, job] = await Promise.all([api.aiRunReviews(runId), api.latestAiReviewJob(runId)])
  aiReviews.value = Object.fromEntries(reviews.map((item) => [item.findingId, item]))
  aiJob.value = job
}

async function reviewWithAi(item: FindingView) {
  reviewPlannerFinding.value = item.id
  reviewPlannerFailed.value = false
  reviewPlannerOpen.value = true
}
async function startBatchAiReview(failedOnly = false) {
  reviewPlannerFinding.value = undefined
  reviewPlannerFailed.value = failedOnly
  reviewPlannerOpen.value = true
}

function pollAiJob(jobId: number) {
  if (aiPollTimer) window.clearTimeout(aiPollTimer)
  aiPollTimer = window.setTimeout(async () => {
    try {
      aiJob.value = await api.aiReviewJob(jobId)
      if (selectedRunId.value) await loadAiReviews(selectedRunId.value)
      if (selectedRunId.value) {
        findings.value = await api.runFindings(selectedRunId.value)
        await loadReportStatus(selectedRunId.value)
      }
      if (aiDetailsOpen.value) await loadAiJobRecords(jobId)
      if (aiJob.value.status === 'PENDING' || aiJob.value.status === 'RUNNING') pollAiJob(jobId)
    } catch (reason) {
      error.value = reason instanceof Error ? reason.message : '工具审核进度加载失败'
    }
  }, 1500)
}

function applyAiSuggestion(item: FindingView) {
  const ai = aiReviews.value[item.id]
  openReview(item)
  if (!ai) return
  if (ai.consolidatedVerdict === 'CONFIRMED') review.triageStatus = 'CONFIRMED'
  if (ai.consolidatedVerdict === 'FALSE_POSITIVE') review.triageStatus = 'FALSE_POSITIVE'
  const reason = ai.verificationReview?.reason ?? ai.primaryReview?.reason ?? ''
  review.comment = `工具复核建议：${aiVerdictLabel(ai.consolidatedVerdict)}；可信度：${aiConfidence(ai.confidence)}。${reason}`
}

async function saveReview() {
  if (!selected.value) return
  saving.value = true
  error.value = ''
  try {
    await api.triageFinding(selected.value.id, {
      ...review,
      expiresAt: review.expiresAt || undefined,
    })
    localStorage.setItem('codesentry-reviewer', review.reviewer)
    if (selectedRunId.value) {
      findings.value = await api.runFindings(selectedRunId.value)
      await loadReportStatus(selectedRunId.value)
    }
    selected.value = null
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '人工复核保存失败'
  } finally {
    saving.value = false
  }
}

const filtered = computed(() =>
  findings.value.filter((item) => {
    const text =
      `${item.title} ${item.description ?? ''} ${item.filePath} ${item.ruleId} ${item.cwe ?? ''}`.toLowerCase()
    return (
      (severity.value === 'ALL' || item.severity === severity.value) &&
      (state.value === 'ALL' || item.resultStatus === state.value) &&
      (reportState.value === 'ALL' || reportStatus(item) === reportState.value) &&
      (!keyword.value || text.includes(keyword.value.toLowerCase()))
    )
  }),
)
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize.value)))
const pagedFindings = computed(() =>
  filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value),
)
const pageFindingIds = computed(() => pagedFindings.value.map((item) => item.id))
const allPageSelected = computed(
  () =>
    pageFindingIds.value.length > 0 &&
    pageFindingIds.value.every((id) => selectedFindingIds.value.includes(id)),
)

function toggleFindingSelection(id: number) {
  selectedFindingIds.value = selectedFindingIds.value.includes(id)
    ? selectedFindingIds.value.filter((item) => item !== id)
    : [...selectedFindingIds.value, id]
}

function togglePageSelection() {
  if (allPageSelected.value) {
    selectedFindingIds.value = selectedFindingIds.value.filter(
      (id) => !pageFindingIds.value.includes(id),
    )
  } else {
    selectedFindingIds.value = [...new Set([...selectedFindingIds.value, ...pageFindingIds.value])]
  }
}

function openBatchReview() {
  if (!selectedFindingIds.value.length) return
  batchReview.triageStatus = 'CONFIRMED'
  batchReview.comment = ''
  batchReview.reviewer = localStorage.getItem('codesentry-reviewer') ?? '本地复核人'
  batchReview.expiresAt = ''
  batchReviewResult.value = ''
  batchReviewOpen.value = true
}

async function saveBatchReview() {
  if (!selectedRunId.value || !selectedFindingIds.value.length) return
  batchReviewSaving.value = true
  error.value = ''
  batchReviewResult.value = ''
  try {
    const result = await api.batchTriageFindings(selectedRunId.value, {
      ...batchReview,
      expiresAt: batchReview.expiresAt || undefined,
      selectors: selectedFindingIds.value.slice(0, 500).map((findingId) => ({ findingId })),
    })
    localStorage.setItem('codesentry-reviewer', batchReview.reviewer)
    findings.value = await api.runFindings(selectedRunId.value)
    await loadReportStatus(selectedRunId.value)
    batchReviewResult.value = `已完成 ${result.matched} 条人工复核${result.unmatched ? `，${result.unmatched} 条未匹配` : ''}`
    selectedFindingIds.value = []
    window.setTimeout(() => {
      batchReviewOpen.value = false
      batchReviewResult.value = ''
    }, 900)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '批量人工复核失败'
  } finally {
    batchReviewSaving.value = false
  }
}

watch([severity, state, reportState, keyword, selectedRunId, selectedProjectId], () => {
  page.value = 1
})
watch(pageSize, () => {
  page.value = 1
})
watch(totalPages, (maximum) => {
  if (page.value > maximum) page.value = maximum
})
watch(selectedProjectId, () => {
  if (!projectRuns.value.some((item) => item.id === selectedRunId.value)) {
    selectedRunId.value = projectRuns.value[0]?.id ?? null
  }
})
watch(selectedRunId, async (runId) => {
  selectedFindingIds.value = []
  batchReviewOpen.value = false
  if (aiPollTimer) {
    window.clearTimeout(aiPollTimer)
    aiPollTimer = undefined
  }
  aiDetailsOpen.value = false
  aiJobRecords.value = []
  aiExecutionFindings.value = []
  if (runId === null) {
    findings.value = []
    includedFindingIds.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    findings.value = await api.runFindings(runId)
    await loadAiReviews(runId)
    await loadReportStatus(runId)
    if (aiJob.value && (aiJob.value.status === 'PENDING' || aiJob.value.status === 'RUNNING')) {
      pollAiJob(aiJob.value.id)
    }
  } catch (reason) {
    findings.value = []
    error.value = reason instanceof Error ? reason.message : '扫描批次问题加载失败'
  } finally {
    loading.value = false
  }
})

onMounted(async () => {
  try {
    businessProjects.value = await api.businessProjects()
    businessProjectId.value = chooseBusinessProject(route.query.businessProjectId)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '项目列表加载失败'
  } finally {
    businessProjectsLoading.value = false
  }
  try {
    const [allRuns, allProjects, config] = await Promise.all([
      api.scanRuns(200),
      api.projects(),
      api.aiReviewConfig(),
    ])
    runs.value = allRuns
    projects.value = allProjects
    aiConfig.value = config
    const requestedRunId = Number(route.query.runId)
    const requestedAssetId = Number(route.query.projectId)
    if (requestedRunId > 0) await openAssetRun(requestedRunId)
    else if (requestedAssetId > 0) {
      const run = runs.value.find((r) => r.projectId === requestedAssetId)
      if (run) await openAssetRun(run.id)
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '扫描批次加载失败'
  } finally {
    initialized = true
  }
})
onBeforeUnmount(() => {
  if (aiPollTimer) window.clearTimeout(aiPollTimer)
})
</script>

<template>
  <AiReviewPlanner
    :open="reviewPlannerOpen"
    :runs="reviewPlannerRuns"
    :finding-id="reviewPlannerFinding"
    :failed-only="reviewPlannerFailed"
    @close="reviewPlannerOpen = false"
    @started="plannedReviews"
  />
  <div class="page-stack">
    <div class="panel" style="padding: 20px">
      <div class="field">
        <label for="ledger-business-project">业务项目</label
        ><select
          id="ledger-business-project"
          class="select"
          v-model="businessProjectId"
          :disabled="businessProjectsLoading || !businessProjects.length"
          @change="changeBusinessProject"
        >
          <option v-if="!businessProjectId" :value="null" disabled>
            {{ businessProjectsLoading ? '正在加载业务项目…' : '请先创建业务项目' }}
          </option>
          <option v-for="p in businessProjects" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </div>
    </div>
    <div v-if="error && !assetDetailsOpen" class="error-banner" role="alert">{{ error }}</div>
    <div v-if="assetDetailsOpen" class="row-actions">
      <button class="button secondary compact" @click="backToProjectLedger">← 返回项目台账</button
      ><span class="muted">{{ selectedProject?.projectName }} · 扫描与审核详情</span>
    </div>
    <ProjectLedger
      v-if="businessProjectId && !assetDetailsOpen"
      :business-project-id="businessProjectId"
      @open-asset="openAssetRun"
    />
    <EmptyState
      v-else-if="!businessProjectsLoading && !assetDetailsOpen"
      title="还没有业务项目"
      description="请先在项目概览中创建业务项目并关联扫描资产，台账将按你创建的项目展示。"
    />
    <div v-if="assetDetailsOpen" class="page-stack">
      <div v-if="!selectedProject?.businessProjectId" class="notice">
        该资产尚未关联业务项目。可在项目概览关联后，从项目台账统一查看；此处仅展示链接指定的历史扫描。
      </div>
      <section class="section-heading">
        <div>
          <h2>资产扫描与审核详情</h2>
          <p>
            保留本批次的扫描结果，包括未审核、审核失败、低可信度及误报项，供工具审核和人工复核。最终报告另按审核条件筛选；各页面统一按问题条数统计；报告按依赖合并展示纳入问题，并列明未纳入项及原因。
          </p>
        </div>
        <span class="section-code">{{
          selectedRun ? `${batchNumber(selectedRun.id)} / ${filtered.length} 条问题` : '未选择批次'
        }}</span>
      </section>
      <div v-if="error" class="error-banner">{{ error }}</div>
      <section class="panel panel-pad">
        <div class="form-grid filter-grid finding-filter-grid">
          <div class="field">
            <label :for="formFieldId + '-1'">扫描资产</label
            ><select
              :id="formFieldId + '-1'"
              v-model.number="selectedProjectId"
              class="select"
              :disabled="!scopedProjects.length"
            >
              <option v-for="project in scopedProjects" :key="project.id" :value="project.id">
                {{ project.projectName }}
              </option></select
            ><small>选择当前业务项目下的资产，再查看对应扫描批次。</small>
          </div>
          <div class="field">
            <label :for="formFieldId + '-2'">扫描批次</label
            ><select
              :id="formFieldId + '-2'"
              v-model.number="selectedRunId"
              class="select"
              :disabled="!projectRuns.length"
            >
              <option v-for="run in projectRuns" :key="run.id" :value="run.id">
                {{ runOptionLabel(run) }}
              </option></select
            ><small
              >{{ selectedProject?.projectName ?? '当前项目' }}共有
              {{ projectRuns.length }} 个批次。</small
            >
          </div>
          <div class="field">
            <label :for="formFieldId + '-3'">检索问题或文件</label
            ><input
              :id="formFieldId + '-3'"
              v-model="keyword"
              class="input"
              placeholder="输入规则、标题或文件路径"
            />
          </div>
          <div class="field">
            <label :for="formFieldId + '-4'">风险等级</label
            ><select :id="formFieldId + '-4'" v-model="severity" class="select">
              <option value="ALL">全部等级</option>
              <option
                v-for="item in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']"
                :key="item"
                :value="item"
              >
                {{ statusLabel(item) }}
              </option>
            </select>
          </div>
          <div class="field">
            <label :for="formFieldId + '-5'">报告状态</label
            ><select :id="formFieldId + '-5'" v-model="reportState" class="select">
              <option value="ALL">全部</option>
              <option>已采纳</option>
              <option>未采纳</option>
              <option>待审核</option>
            </select>
          </div>
          <div class="field">
            <label :for="formFieldId + '-6'">变化状态</label
            ><select :id="formFieldId + '-6'" v-model="state" class="select">
              <option value="ALL">全部状态</option>
              <option
                v-for="item in ['NEW', 'EXISTING', 'FIXED', 'REOPENED']"
                :key="item"
                :value="item"
              >
                {{ statusLabel(item) }}
              </option>
            </select>
          </div>
        </div>
        <div v-if="selectedRun" class="batch-context">
          <div>
            <span>当前批次</span><strong>{{ batchNumber(selectedRun.id) }}</strong>
          </div>
          <div>
            <span>扫描时间</span
            ><strong
              ><time class="datetime-text">{{ formatTime(selectedRun.createdAt) }}</time></strong
            >
          </div>
          <div>
            <span>扫描类型</span><strong>{{ modeLabel(selectedRun) }}</strong>
          </div>
          <div><span>批次状态</span><StatusBadge :value="selectedRun.status" /></div>
          <div>
            <span>台账统计</span
            ><strong
              >{{ findings.length }} 条台账 / {{ batchNewCount }} 条新增 /
              {{ batchFixedCount }} 条修复</strong
            >
          </div>
        </div>
        <div v-if="aiConfig.enabled && selectedRun" class="ai-batch-bar">
          <div>
            <span class="eyebrow">工具辅助复核</span>
            <p>
              最多
              {{ aiConfig.maxConcurrency ?? 1 }}
              组并行，每组依次完成初审、复审。审核进度按已覆盖的问题条数统计；内部合并调用不影响问题数量。审核人员再结合业务和调用链确认，工具建议不会覆盖人工结论。
            </p>
          </div>
          <div v-if="aiJob" class="ai-job-progress">
            <strong>{{ reviewedIssueCount }} / {{ findings.length }} 条已审核</strong
            ><span
              >{{ aiJobStatusLabel(aiJob.status)
              }}<template v-if="aiJob.failedFindings">
                · {{ aiJob.failedFindings }} 组失败</template
              ></span
            >
          </div>
          <div v-if="aiJob" class="ai-batch-actions">
            <button class="button secondary" @click="openAiDetails">执行详情</button
            ><button
              v-if="['PARTIAL', 'FAILED'].includes(aiJob.status) && aiJob.failedFindings"
              class="button secondary"
              :disabled="startingAiBatch"
              @click="startBatchAiReview(true)"
            >
              重试失败项</button
            ><button
              v-if="['PENDING', 'RUNNING'].includes(aiJob.status)"
              class="button danger"
              :disabled="stoppingAi"
              @click="stopAiReview"
            >
              {{ stoppingAi ? '停止中…' : '停止工具审核' }}
            </button>
          </div>
        </div>
      </section>
      <section class="panel">
        <div v-if="!loading && filtered.length" class="batch-review-toolbar">
          <div class="batch-review-selection">
            <label
              ><input
                type="checkbox"
                :checked="allPageSelected"
                @change="togglePageSelection"
              /><span>选择当前页</span></label
            ><strong>{{ selectedFindingIds.length }} 条已选</strong
            ><button
              v-if="selectedFindingIds.length"
              type="button"
              class="text-button"
              @click="selectedFindingIds = []"
            >
              清空
            </button>
          </div>
          <div class="batch-review-actions">
            <span
              >工具审核处理当前批次，相同依赖文件和版本的 CVE 合并复核；人工复核仅处理已选问题</span
            ><button
              v-if="aiConfig.enabled && selectedRun"
              class="button secondary compact"
              :disabled="
                startingAiBatch || aiJob?.status === 'RUNNING' || aiJob?.status === 'PENDING'
              "
              @click="startBatchAiReview(false)"
            >
              {{ startingAiBatch ? '正在启动…' : '批量工具审核' }}</button
            ><button
              class="button primary compact"
              :disabled="!selectedFindingIds.length"
              @click="openBatchReview"
            >
              批量人工复核
            </button>
          </div>
        </div>
        <div v-if="loading" class="empty-state compact-empty">
          <h3>正在加载扫描批次</h3>
          <p>正在读取 {{ selectedRunId ? batchNumber(selectedRunId) : '' }} 的问题明细。</p>
        </div>
        <div v-else-if="filtered.length" class="table-scroll findings-table">
          <table class="data-table">
            <thead>
              <tr>
                <th class="selection-column">
                  <input
                    type="checkbox"
                    aria-label="选择当前页全部问题"
                    :checked="allPageSelected"
                    @change="togglePageSelection"
                  />
                </th>
                <th>扫描批次</th>
                <th>等级</th>
                <th>问题</th>
                <th>位置</th>
                <th>扫描器 / 规则</th>
                <th>变化</th>
                <th>工具建议</th>
                <th>人工结论</th>
                <th>报告状态</th>
                <th class="actions-heading">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in pagedFindings"
                :key="`${item.scanRunId}-${item.id}`"
                :class="{ 'is-selected': selectedFindingIds.includes(item.id) }"
              >
                <td class="selection-column">
                  <input
                    type="checkbox"
                    :aria-label="`选择问题 ${findingTitle(item)}`"
                    :checked="selectedFindingIds.includes(item.id)"
                    @change="toggleFindingSelection(item.id)"
                  />
                </td>
                <td>
                  <RouterLink :to="`/scans/${item.scanRunId}`" class="batch-link">{{
                    batchNumber(item.scanRunId)
                  }}</RouterLink>
                </td>
                <td><StatusBadge :value="item.severity" /></td>
                <td>
                  <strong class="finding-title-clamp" :title="item.title">{{
                    findingTitle(item)
                  }}</strong>
                  <div class="finding-tags">
                    <span>{{ categoryLabel(item.category) }}</span><span v-if="item.cwe"
                      >弱点编号 {{ item.cwe }}</span
                    >
                  </div>
                  <button type="button" class="snippet-trigger" @click="snippetFinding = item">
                    <span>{{ isRedacted(item) ? '◈' : '⌁' }}</span>
                    {{ isRedacted(item) ? '查看文件说明' : '查看原始内容' }}
                  </button>
                </td>
                <td>
                  <span class="mono finding-location" :title="findingLocation(item)">{{
                    findingLocation(item)
                  }}</span><small v-if="isDependencyVulnerability(item)" class="muted"
                    >依赖组件漏洞，无源码行号</small
                  >
                </td>
                <td>
                  <div>{{ scannerLabel(item.scanner) }}</div>
                  <div class="mono finding-rule" :title="item.ruleId">{{ item.ruleId }}</div>
                </td>
                <td><StatusBadge :value="item.resultStatus" /></td>
                <td>
                  <div v-if="aiReviews[item.id]" class="ai-review-cell">
                    <strong>{{
                      aiVerdictLabel(aiReviews[item.id].consolidatedVerdict)
                    }}</strong><span v-if="aiReviews[item.id].confidenceAvailable"
                      >可信度 {{ aiConfidence(aiReviews[item.id].confidence) }}</span
                    ><small v-if="aiReviews[item.id].errorMessage">{{
                      aiReviews[item.id].errorMessage
                    }}</small
                    ><small v-if="aiReviews[item.id].disagreement"
                      >工具结果存在分歧，必须人工处理</small
                    ><button
                      v-if="aiReviews[item.id].status !== 'FAILED'"
                      type="button"
                      class="snippet-trigger"
                      @click="applyAiSuggestion(item)"
                    >
                      带入人工复核</button
                    ><button
                      v-else
                      type="button"
                      class="snippet-trigger"
                      :disabled="aiReviewingId === item.id"
                      @click="reviewWithAi(item)"
                    >
                      {{ aiReviewingId === item.id ? '重试中…' : '重新审核' }}
                    </button>
                  </div>
                  <button
                    v-else-if="aiConfig.enabled"
                    class="button secondary compact"
                    :disabled="aiReviewingId === item.id"
                    @click="reviewWithAi(item)"
                  >
                    {{ aiReviewingId === item.id ? '审核中…' : '工具初审' }}</button><span
                    v-else
                    class="muted"
                    >未启用</span
                  >
                </td>
                <td>
                  <StatusBadge v-if="item.triageStatus" :value="item.triageStatus" /><span
                    v-else
                    class="muted"
                    >等待复核</span
                  >
                  <div v-if="item.reviewer" class="mono finding-reviewer">
                    复核人：{{ item.reviewer }}
                  </div>
                </td>
                <td>
                  <strong>{{ reportStatus(item) }}</strong><small
                    v-if="exclusionReasons[item.id]"
                    class="exclusion-reason"
                    >{{ exclusionReasons[item.id] }}</small
                  >
                </td>
                <td class="actions-cell">
                  <button class="button secondary compact" @click="openReview(item)">
                    {{ item.triageStatus ? '更新复核' : '人工复核' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="!loading && filtered.length" class="finding-card-list">
          <article
            v-for="item in pagedFindings"
            :key="`mobile-${item.scanRunId}-${item.id}`"
            class="finding-card"
          >
            <header>
              <label class="mobile-select"
                ><input
                  type="checkbox"
                  :checked="selectedFindingIds.includes(item.id)"
                  @change="toggleFindingSelection(item.id)"
                />选择</label
              ><StatusBadge :value="item.severity" /><StatusBadge :value="item.resultStatus" />
            </header>
            <h3>{{ findingTitle(item) }}</h3>
            <p v-if="findingTitle(item) !== item.title" class="muted">{{ item.title }}</p>
            <code>{{ findingLocation(item) }}</code>
            <dl>
              <div>
                <dt>扫描器</dt>
                <dd>{{ scannerLabel(item.scanner) }}</dd>
              </div>
              <div>
                <dt>规则编号</dt>
                <dd>{{ item.ruleId }}</dd>
              </div>
              <div>
                <dt>工具建议</dt>
                <dd>
                  {{ aiVerdictLabel(aiReviews[item.id]?.consolidatedVerdict) }} /
                  {{ aiConfidence(aiReviews[item.id]?.confidence) }}
                </dd>
              </div>
              <div>
                <dt>报告状态</dt>
                <dd>
                  {{ reportStatus(item)
                  }}<small v-if="exclusionReasons[item.id]" class="exclusion-reason">{{
                    exclusionReasons[item.id]
                  }}</small>
                </dd>
              </div>
              <div>
                <dt>人工结论</dt>
                <dd>
                  <StatusBadge v-if="item.triageStatus" :value="item.triageStatus" /><span v-else
                    >等待复核</span
                  >
                </dd>
              </div>
            </dl>
            <button
              v-if="aiConfig.enabled && !aiReviews[item.id]"
              class="button secondary"
              :disabled="aiReviewingId === item.id"
              @click="reviewWithAi(item)"
            >
              {{ aiReviewingId === item.id ? '工具审核中…' : '工具初审' }}
            </button>
            <button
              v-if="aiReviews[item.id]"
              class="button secondary"
              @click="applyAiSuggestion(item)"
            >
              将工具建议带入人工复核
            </button>
            <button class="button secondary" @click="snippetFinding = item">查看原始内容</button>
            <button class="button secondary" @click="openReview(item)">
              {{ item.triageStatus ? '更新复核' : '人工复核' }}
            </button>
          </article>
        </div>
        <footer v-if="!loading && filtered.length" class="table-pager">
          <span>第 {{ page }} / {{ totalPages }} 页，共 {{ filtered.length }} 条问题</span>
          <div class="pager-controls">
            <label class="pager-field"
              >每页<select
                v-model.number="pageSize"
                class="pager-select"
                aria-label="问题台账每页条数"
              >
                <option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条</option>
              </select></label
            >
            <label class="pager-field"
              >跳至<select v-model.number="page" class="pager-select" aria-label="问题台账选择页数">
                <option v-for="number in totalPages" :key="number" :value="number">
                  第 {{ number }} 页
                </option>
              </select></label
            >
            <div class="pager-buttons">
              <button class="button secondary compact" :disabled="page <= 1" @click="page--">
                上一页</button
              ><button
                class="button secondary compact"
                :disabled="page >= totalPages"
                @click="page++"
              >
                下一页
              </button>
            </div>
          </div>
        </footer>
        <EmptyState
          v-if="!loading && !filtered.length"
          title="当前批次没有匹配的问题"
          :description="
            selectedRun
              ? `${batchNumber(selectedRun.id)} 没有符合当前筛选条件的问题；若刚完成扫描，请先执行 工具审核或人工复核。`
              : '尚无可查询的扫描批次。'
          "
        />
      </section>

      <Teleport to="body">
        <div
          v-if="aiDetailsOpen && aiJob"
          class="modal-backdrop ai-detail-backdrop"
          @click.self="aiDetailsOpen = false"
        >
          <section
            class="ai-detail-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="工具批量审核执行详情"
          >
            <header class="ai-detail-head">
              <div>
                <span class="eyebrow">工具审核执行详情</span>
                <h2>{{ batchNumber(aiJob.scanRunId) }} 批量审核执行详情</h2>
                <p>
                  依赖漏洞按文件和版本合并为审核组；整批结束后自动补跑一次失败组，成功组不会重复调用。
                </p>
              </div>
              <div class="ai-detail-head-actions">
                <button
                  v-if="['PARTIAL', 'FAILED'].includes(aiJob.status) && aiJob.failedFindings"
                  class="button secondary compact"
                  :disabled="startingAiBatch"
                  @click="startBatchAiReview(true)"
                >
                  重试失败项</button
                ><button
                  v-if="['PENDING', 'RUNNING'].includes(aiJob.status)"
                  class="button danger compact"
                  :disabled="stoppingAi"
                  @click="stopAiReview"
                >
                  {{ stoppingAi ? '停止中…' : '停止工具审核' }}</button
                ><button
                  class="button secondary compact"
                  :disabled="aiDetailsLoading"
                  @click="loadAiJobRecords(aiJob.id)"
                >
                  {{ aiDetailsLoading ? '刷新中…' : '刷新' }}</button
                ><button
                  class="icon-button"
                  aria-label="关闭 工具执行详情"
                  @click="aiDetailsOpen = false"
                >
                  ×
                </button>
              </div>
            </header>
            <div class="ai-detail-metrics">
              <div>
                <span>任务编号</span><strong>#{{ aiJob.id }}</strong>
              </div>
              <div>
                <span>执行方式</span
                ><strong>{{
                  (aiJob.concurrency ?? 1) > 1
                    ? `并行审核 · 最多 ${aiJob.concurrency} 组`
                    : '工具复核'
                }}</strong>
              </div>
              <div>
                <span>任务状态</span><strong>{{ aiJobStatusLabel(aiJob.status) }}</strong>
              </div>
              <div>
                <span>完成组 / 失败组</span
                ><strong>{{ aiJob.completedFindings }} / {{ aiJob.failedFindings }}</strong>
              </div>
              <div>
                <span>开始时间</span
                ><strong
                  ><time class="datetime-text">{{
                    formatTime(aiJob.startedAt ?? aiJob.createdAt)
                  }}</time></strong
                >
              </div>
            </div>
            <div class="ai-detail-progress">
              <div>
                <span>本批次问题覆盖</span
                ><strong>{{ reviewedIssueCount }} / {{ findings.length }} 条已审核</strong>
              </div>
              <div class="progress-meter">
                <span
                  :style="{
                    width: `${findings.length ? Math.round((reviewedIssueCount / findings.length) * 100) : 0}%`,
                  }"
                ></span>
              </div>
              <b
                >{{
                  findings.length ? Math.round((reviewedIssueCount / findings.length) * 100) : 0
                }}%</b
              >
            </div>
            <div class="ai-review-flow" aria-label="工具检查执行步骤">
              <div><b>01</b><span>本地提取真实源码</span></div>
              <i>→</i>
              <div><b>02</b><span>工具初审</span></div>
              <i>→</i>
              <div><b>03</b><span>工具复审</span></div>
              <i>→</i>
              <div><b>04</b><span>生成工具建议</span></div>
            </div>
            <div v-if="aiJob.errorMessage || aiDetailError" class="error-banner ai-detail-error">
              {{ aiDetailError || aiJob.errorMessage }}
            </div>
            <div class="ai-detail-table-wrap">
              <table class="data-table ai-detail-table">
                <thead>
                  <tr>
                    <th>问题</th>
                    <th>执行状态</th>
                    <th>工具初审</th>
                    <th>工具复审</th>
                    <th>失败信息</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in aiExecutionRows" :key="`ai-detail-${row.finding.id}`">
                    <td>
                      <strong>{{ findingTitle(row.finding) }}</strong><code>{{
                        findingLocation(row.finding)
                      }}</code>
                    </td>
                    <td>
                      <span class="ai-stage" :class="`is-${row.stage.toLowerCase()}`">{{
                        aiStageLabel(row.stage)
                      }}</span>
                    </td>
                    <td>
                      <template v-if="row.primary"
                        ><b>{{ aiVerdictLabel(row.primary.verdict) }}</b
                        ><span>可信度 {{ aiConfidence(row.primary.confidence) }}</span
                        ><small v-if="row.primary.reason">{{ row.primary.reason }}</small></template
                      ><span v-else class="muted">等待执行</span>
                    </td>
                    <td>
                      <template v-if="row.verification"
                        ><b>{{ aiVerdictLabel(row.verification.verdict) }}</b
                        ><span>可信度 {{ aiConfidence(row.verification.confidence) }}</span
                        ><small v-if="row.verification.reason">{{
                          row.verification.reason
                        }}</small></template
                      ><span v-else class="muted">—</span>
                    </td>
                    <td>
                      <span v-if="row.failure" class="danger-text">{{
                        row.failure.errorMessage || '工具服务调用失败'
                      }}</span><span v-else class="muted">—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>
      </Teleport>

      <Teleport to="body">
        <div v-if="batchReviewOpen" class="modal-backdrop" @click.self="batchReviewOpen = false">
          <section
            class="review-dialog batch-review-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="批量人工复核"
          >
            <header class="review-head">
              <div>
                <span class="eyebrow">批量人工复核</span>
                <h2>统一处理 {{ selectedFindingIds.length }} 条问题</h2>
                <p>仅更新当前选中的问题，不删除扫描证据；后续仍可逐条修改。</p>
              </div>
              <button
                class="icon-button"
                aria-label="关闭批量人工复核"
                @click="batchReviewOpen = false"
              >
                ×
              </button>
            </header>
            <form class="review-form" @submit.prevent="saveBatchReview">
              <div class="field">
                <label for="batch-triage-status">复核结论</label
                ><select
                  id="batch-triage-status"
                  v-model="batchReview.triageStatus"
                  class="select"
                  required
                >
                  <option value="CONFIRMED">确认风险</option>
                  <option value="FALSE_POSITIVE">误报</option>
                  <option value="ACCEPTED_RISK">接受风险</option>
                  <option value="FIXED_PENDING">已整改，等待复扫验证</option>
                </select>
              </div>
              <div class="field">
                <label for="batch-triage-reviewer">复核人</label
                ><input
                  id="batch-triage-reviewer"
                  v-model.trim="batchReview.reviewer"
                  class="input"
                  maxlength="128"
                  required
                />
              </div>
              <div class="field span-2">
                <label for="batch-triage-comment">统一复核说明</label
                ><textarea
                  id="batch-triage-comment"
                  v-model="batchReview.comment"
                  class="textarea"
                  maxlength="2000"
                  placeholder="记录本批问题的统一判断依据、整改建议或风险接受原因"
                ></textarea>
              </div>
              <div v-if="batchReview.triageStatus === 'ACCEPTED_RISK'" class="field span-2">
                <label for="batch-triage-expires">风险接受到期时间（可选）</label
                ><input
                  id="batch-triage-expires"
                  v-model="batchReview.expiresAt"
                  type="datetime-local"
                  class="input"
                />
              </div>
              <div v-if="batchReviewResult" class="success-banner span-2">
                {{ batchReviewResult }}
              </div>
              <div class="form-actions span-2">
                <button type="button" class="button secondary" @click="batchReviewOpen = false">
                  取消</button
                ><button class="button primary" :disabled="batchReviewSaving">
                  {{
                    batchReviewSaving
                      ? '正在批量保存…'
                      : `保存 ${selectedFindingIds.length} 条复核结论`
                  }}
                </button>
              </div>
            </form>
          </section>
        </div>
      </Teleport>

      <Teleport to="body">
        <div v-if="selected" class="modal-backdrop" @click.self="selected = null">
          <section class="review-dialog" role="dialog" aria-modal="true" aria-label="人工复核">
            <header class="review-head">
              <div>
                <span class="eyebrow">人工复核</span>
                <h2>{{ findingTitle(selected) }}</h2>
              </div>
              <button class="icon-button" aria-label="关闭" @click="selected = null">×</button>
            </header>
            <div class="review-context">
              <div>
                <span>扫描批次</span
                ><RouterLink :to="`/scans/${selected.scanRunId}`" class="batch-link">{{
                  batchNumber(selected.scanRunId)
                }}</RouterLink>
              </div>
              <div><span>风险等级</span><StatusBadge :value="selected.severity" /></div>
              <div>
                <span>扫描规则</span
                ><b>{{ scannerLabel(selected.scanner) }} / {{ selected.ruleId }}</b>
              </div>
              <div>
                <span>{{ findingLocationLabel(selected) }}</span><code>{{
                  findingLocation(selected)
                }}</code
                ><small v-if="isDependencyVulnerability(selected)"
                  >依赖组件漏洞不对应源码行号</small
                >
              </div>
              <div v-if="selected.codeSnippet" class="review-code">
                <span>{{ isRedacted(selected) ? '原始内容暂不可用' : '代码片段' }}</span><button
                  type="button"
                  class="snippet-trigger"
                  @click="snippetFinding = selected"
                >
                  <span>{{ isRedacted(selected) ? '◈' : '⌁' }}</span>
                  {{ isRedacted(selected) ? '查看文件说明' : '查看原始内容' }}
                </button>
              </div>
              <div v-if="selected.description">
                <span>问题说明</span>
                <p>{{ selected.description }}</p>
              </div>
              <div v-if="selected.recommendation">
                <span>整改建议</span>
                <p>{{ selected.recommendation }}</p>
              </div>
            </div>
            <form class="review-form" @submit.prevent="saveReview">
              <div class="field">
                <label for="triage-status">复核结论</label
                ><select id="triage-status" v-model="review.triageStatus" class="select" required>
                  <option value="CONFIRMED">确认风险</option>
                  <option value="FALSE_POSITIVE">误报</option>
                  <option value="ACCEPTED_RISK">接受风险</option>
                  <option value="FIXED_PENDING">已整改，等待复扫验证</option>
                </select>
              </div>
              <div class="field">
                <label for="triage-reviewer">复核人</label
                ><input
                  id="triage-reviewer"
                  v-model.trim="review.reviewer"
                  class="input"
                  maxlength="128"
                  required
                />
              </div>
              <div class="field span-2">
                <label for="triage-comment">复核说明</label
                ><textarea
                  id="triage-comment"
                  v-model="review.comment"
                  class="textarea"
                  maxlength="2000"
                  placeholder="记录判断依据、整改建议或风险接受原因"
                ></textarea>
              </div>
              <div v-if="review.triageStatus === 'ACCEPTED_RISK'" class="field span-2">
                <label for="triage-expires">风险接受到期时间（可选）</label
                ><input
                  id="triage-expires"
                  v-model="review.expiresAt"
                  type="datetime-local"
                  class="input"
                />
              </div>
              <div class="form-actions span-2">
                <button type="button" class="button secondary" @click="selected = null">取消</button
                ><button class="button primary" :disabled="saving">
                  {{ saving ? '正在保存…' : '保存复核结论' }}
                </button>
              </div>
            </form>
          </section>
        </div>
      </Teleport>

      <Teleport to="body">
        <div
          v-if="snippetFinding"
          class="modal-backdrop snippet-backdrop"
          @click.self="snippetFinding = null"
        >
          <section
            class="snippet-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="问题原始内容查看器"
          >
            <header class="snippet-head">
              <div>
                <span class="eyebrow">{{
                  isRedacted(snippetFinding) ? '原始内容暂不可用' : '本机原始内容'
                }}</span>
                <h2>{{ findingTitle(snippetFinding) }}</h2>
              </div>
              <button class="icon-button" aria-label="关闭原始内容" @click="snippetFinding = null">
                ×
              </button>
            </header>
            <div class="snippet-meta">
              <div>
                <span>扫描批次</span
                ><RouterLink :to="`/scans/${snippetFinding.scanRunId}`" class="batch-link">{{
                  batchNumber(snippetFinding.scanRunId)
                }}</RouterLink>
              </div>
              <div>
                <span>扫描器 / 规则</span
                ><strong
                  >{{ scannerLabel(snippetFinding.scanner) }} / {{ snippetFinding.ruleId }}</strong
                >
              </div>
              <div class="snippet-path">
                <span>{{ findingLocationLabel(snippetFinding) }}</span><code>{{
                  findingLocation(snippetFinding)
                }}</code
                ><small v-if="isDependencyVulnerability(snippetFinding)"
                  >依赖组件漏洞不对应源码行号</small
                >
              </div>
            </div>
            <template v-if="isRedacted(snippetFinding)">
              <div class="redacted-view">
                <div class="redacted-mark"><span>!</span></div>
                <div class="redacted-copy">
                  <span>无法文本预览</span>
                  <h3>暂时无法读取代码片段</h3>
                  <p>
                    扫描快照可能已清理、文件过大或无法作为文本预览。可读取的文本会显示命中行及上下文，请根据文件位置进一步核验。
                  </p>
                </div>
                <div class="redacted-location">
                  <span>本地文件位置</span
                  ><code>{{ snippetFinding.filePath }}:{{ snippetFinding.startLine ?? '-' }}</code
                  ><small>请使用对应证书工具核验；确认暴露后及时移除并轮换凭据。</small>
                </div>
              </div>
            </template>
            <template v-else>
              <div class="original-finding-copy">
                <div>
                  <span>原始标题</span>
                  <p>{{ snippetFinding.title }}</p>
                </div>
                <div v-if="snippetFinding.description">
                  <span>原始描述</span>
                  <p>{{ snippetFinding.description }}</p>
                </div>
                <div v-if="snippetFinding.recommendation">
                  <span>整改建议</span>
                  <p>{{ snippetFinding.recommendation }}</p>
                </div>
              </div>
              <div class="code-viewer-head">
                <span>原始代码或扫描证据</span
                ><span>仅在本机页面展示 · 正式报告与工具复核仍会脱敏</span>
              </div>
              <pre
                v-if="snippetFinding.codeSnippet"
                class="code-viewer"
              ><code>{{ snippetFinding.codeSnippet }}</code></pre>
              <div v-else class="source-empty">
                <strong>扫描器未提供代码片段</strong
                ><span>上方已完整展示扫描器返回的原始标题和描述，可根据文件位置继续核验。</span>
              </div>
            </template>
          </section>
        </div>
      </Teleport>
    </div>
  </div>
</template>

<style scoped>
.exclusion-reason {
  display: block;
  margin-top: 0.4rem;
  max-width: 20rem;
  color: var(--muted);
  line-height: 1.5;
}
</style>
