<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '@/api/codesentry'
import { categoryLabel, findingTitle, scannerLabel, statusLabel } from '@/utils/labels'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import type { AiReviewConfiguration, AiReviewJob, AiReviewRecord, AiReviewSummary, FindingView, ScanRun, TriageStatus } from '@/types'

const findings = ref<FindingView[]>([])
const runs = ref<ScanRun[]>([])
const selectedRunId = ref<number | null>(null)
const severity = ref('ALL')
const state = ref('ALL')
const keyword = ref('')
const error = ref('')
const loading = ref(false)
const page = ref(1)
const pageSize = 25
const selected = ref<FindingView | null>(null)
const snippetFinding = ref<FindingView | null>(null)
const saving = ref(false)
const aiConfig = ref<AiReviewConfiguration>({ enabled: false, provider: 'OPENAI_COMPATIBLE' })
const aiReviews = ref<Record<number, AiReviewSummary>>({})
const aiReviewingId = ref<number | null>(null)
const aiJob = ref<AiReviewJob | null>(null)
const startingAiBatch = ref(false)
const stoppingAi = ref(false)
async function stopAiReview() {
  if (!aiJob.value || !window.confirm('确认停止工具审核？已完成的审核保留，剩余问题不再执行。')) return
  stoppingAi.value = true
  try {
    aiJob.value = await api.cancelAiReview(aiJob.value.id)
    if (aiPollTimer) window.clearTimeout(aiPollTimer)
    await loadAiReviews(aiJob.value.scanRunId)
    if (aiDetailsOpen.value) await loadAiJobRecords(aiJob.value!.id)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '停止工具审核失败' }
  finally { stoppingAi.value = false }
}
const aiDetailsOpen = ref(false)
const aiDetailsLoading = ref(false)
const aiDetailError = ref('')
const aiJobRecords = ref<AiReviewRecord[]>([])
const aiExecutionFindings = ref<FindingView[]>([])
let aiPollTimer: number | undefined
const review = reactive({ triageStatus: 'CONFIRMED' as TriageStatus, comment: '', reviewer: '本地复核人', expiresAt: '' })

const isRedacted = (item: FindingView) => item.codeSnippet?.trim() === '[REDACTED]'
const selectedRun = computed(() => runs.value.find((item) => item.id === selectedRunId.value))
const batchNewCount = computed(() => findings.value.filter((item) => item.resultStatus === 'NEW' || item.resultStatus === 'REOPENED').length)
const batchFixedCount = computed(() => findings.value.filter((item) => item.resultStatus === 'FIXED').length)
const batchNumber = (id: number) => `#${String(id).padStart(5, '0')}`
const formatTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
const modeLabel = (run: ScanRun) => run.scanMode === 'FULL' ? '全量扫描' : '增量扫描'
const runOptionLabel = (run: ScanRun) => `${batchNumber(run.id)} · ${modeLabel(run)} · ${formatTime(run.createdAt)}`

function openReview(item: FindingView) {
  selected.value = item
  review.triageStatus = (item.triageStatus as TriageStatus) ?? 'CONFIRMED'
  review.comment = item.triageComment ?? ''
  review.reviewer = item.reviewer ?? localStorage.getItem('codesentry-reviewer') ?? '本地复核人'
  review.expiresAt = item.expiresAt?.slice(0, 16) ?? ''
}

const aiVerdictLabel = (value?: string) => ({
  CONFIRMED: '确认风险', FALSE_POSITIVE: '疑似误报', CONDITIONAL: '条件风险',
  INSUFFICIENT_EVIDENCE: '证据不足', NEEDS_HUMAN: '工具结论待确认', AI_ERROR: '审核失败', CANCELLED: '审核已停止',
}[value ?? ''] ?? '尚未审核')
const aiConfidence = (value?: number) => value == null ? '-' : `${Number((value * 100).toFixed(2))}%`
const aiJobStatusLabel = (value?: AiReviewJob['status']) => ({
  PENDING: '等待执行', RUNNING: '正在审核', COMPLETED: '审核完成', PARTIAL: '部分完成', FAILED: '审核失败', CANCELLED: '已停止',
} as Record<string, string>)[value ?? ''] ?? '未知状态'
const aiJobProgress = computed(() => {
  if (!aiJob.value?.totalFindings) return 0
  return Math.min(100, Math.round((aiJob.value.completedFindings + aiJob.value.failedFindings)
    / aiJob.value.totalFindings * 100))
})
const aiExecutionRows = computed(() => aiExecutionFindings.value.map((finding) => {
  const records = aiJobRecords.value.filter((record) => record.findingId === finding.id).sort((a, b) => b.id - a.id)
  const retained = !records.length && aiReviews.value[finding.id]?.status !== 'FAILED' ? aiReviews.value[finding.id] : undefined
  const primary = records.find((record) => record.reviewRound === 'PRIMARY') ?? retained?.primaryReview
  const verification = records.find((record) => record.reviewRound === 'VERIFY' && (!primary || record.id > primary.id)) ?? retained?.verificationReview
  const failure = records[0]?.reviewRound === 'ERROR' ? records[0] : undefined
  const stage = failure ? 'FAILED' : verification ? 'COMPLETED' : aiJob.value?.status === 'CANCELLED' ? 'CANCELLED' : primary ? 'VERIFYING' : 'PENDING'
  return { finding, primary, verification, failure, stage }
}))
const aiStageLabel = (value: string) => ({
  PENDING: '等待初审', VERIFYING: '复审中', COMPLETED: '复核完成', FAILED: '执行失败', CANCELLED: '已停止',
}[value] ?? value)

async function loadAiJobRecords(jobId: number) {
  aiDetailsLoading.value = true
  aiDetailError.value = ''
  try {
    const [records, sourceFindings] = await Promise.all([api.aiReviewJobRecords(jobId), api.aiReviewJobFindings(jobId)])
    aiJobRecords.value = records; aiExecutionFindings.value = sourceFindings
  }
  catch (reason) { aiDetailError.value = reason instanceof Error ? reason.message : '工具执行详情加载失败' }
  finally { aiDetailsLoading.value = false }
}

async function openAiDetails() {
  if (!aiJob.value) return
  aiDetailsOpen.value = true
  await loadAiJobRecords(aiJob.value.id)
}

async function loadAiReviews(runId: number) {
  if (!aiConfig.value.enabled) { aiReviews.value = {}; aiJob.value = null; return }
  const [reviews, job] = await Promise.all([api.aiRunReviews(runId), api.latestAiReviewJob(runId)])
  aiReviews.value = Object.fromEntries(reviews.map((item) => [item.findingId, item]))
  aiJob.value = job
}

async function reviewWithAi(item: FindingView) {
  aiReviewingId.value = item.id; error.value = ''
  try {
    const summary = await api.aiReviewFinding(item.id)
    aiReviews.value = { ...aiReviews.value, [item.id]: summary }
    if (selectedRunId.value) findings.value = await api.runFindings(selectedRunId.value)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '工具审核失败' }
  finally { aiReviewingId.value = null }
}

async function startBatchAiReview(failedOnly = false) {
  if (!selectedRunId.value) return
  startingAiBatch.value = true; error.value = ''
  try {
    aiJob.value = await (failedOnly ? api.aiRetryFailedRun(selectedRunId.value) : api.aiReviewRun(selectedRunId.value))
    aiJobRecords.value = []
    pollAiJob(aiJob.value.id)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '批量工具审核启动失败' }
  finally { startingAiBatch.value = false }
}

function pollAiJob(jobId: number) {
  if (aiPollTimer) window.clearTimeout(aiPollTimer)
  aiPollTimer = window.setTimeout(async () => {
    try {
      aiJob.value = await api.aiReviewJob(jobId)
      if (selectedRunId.value) await loadAiReviews(selectedRunId.value)
      if (selectedRunId.value) findings.value = await api.runFindings(selectedRunId.value)
      if (aiDetailsOpen.value) await loadAiJobRecords(jobId)
      if (aiJob.value.status === 'PENDING' || aiJob.value.status === 'RUNNING') pollAiJob(jobId)
    } catch (reason) { error.value = reason instanceof Error ? reason.message : '工具审核进度加载失败' }
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
  saving.value = true; error.value = ''
  try {
    const updated = await api.triageFinding(selected.value.id, {
      ...review, expiresAt: review.expiresAt || undefined,
    })
    localStorage.setItem('codesentry-reviewer', review.reviewer)
    if (selectedRunId.value) findings.value = await api.runFindings(selectedRunId.value)
    selected.value = null
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '人工复核保存失败' }
  finally { saving.value = false }
}

const filtered = computed(() => findings.value.filter((item) => {
  const text = `${item.title} ${item.description ?? ''} ${item.filePath} ${item.ruleId} ${item.cwe ?? ''}`.toLowerCase()
  return (severity.value === 'ALL' || item.severity === severity.value)
    && (state.value === 'ALL' || item.resultStatus === state.value)
    && (!keyword.value || text.includes(keyword.value.toLowerCase()))
}))
const totalPages = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))
const pagedFindings = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))
watch([severity, state, keyword, selectedRunId], () => { page.value = 1 })
watch(selectedRunId, async (runId) => {
  if (aiPollTimer) { window.clearTimeout(aiPollTimer); aiPollTimer = undefined }
  aiDetailsOpen.value = false
  aiJobRecords.value = []
  aiExecutionFindings.value = []
  if (runId === null) { findings.value = []; return }
  loading.value = true; error.value = ''
  try {
    findings.value = await api.runFindings(runId)
    await loadAiReviews(runId)
    if (aiJob.value && (aiJob.value.status === 'PENDING' || aiJob.value.status === 'RUNNING')) {
      pollAiJob(aiJob.value.id)
    }
  }
  catch (reason) { findings.value = []; error.value = reason instanceof Error ? reason.message : '扫描批次问题加载失败' }
  finally { loading.value = false }
})

onMounted(async () => {
  try {
    const [allRuns, config] = await Promise.all([api.scanRuns(200), api.aiReviewConfig()])
    runs.value = allRuns
    aiConfig.value = config
    selectedRunId.value = runs.value[0]?.id ?? null
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '扫描批次加载失败' }
})
onBeforeUnmount(() => { if (aiPollTimer) window.clearTimeout(aiPollTimer) })
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>问题台账</h2><p>保留本批次的扫描结果，包括未审核、审核失败、低可信度及误报项，供工具审核和人工复核。最终报告另按审核条件筛选。</p></div><span class="section-code">{{ selectedRun ? `${batchNumber(selectedRun.id)} / ${filtered.length} 个问题` : '未选择批次' }}</span></section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <section class="panel panel-pad">
      <div class="form-grid filter-grid finding-filter-grid">
        <div class="field"><label>扫描批次</label><select v-model.number="selectedRunId" class="select" :disabled="!runs.length"><option v-for="run in runs" :key="run.id" :value="run.id">{{ runOptionLabel(run) }}</option></select><small>默认展示最近一次扫描，可切换查询任意历史批次。</small></div>
        <div class="field"><label>检索问题或文件</label><input v-model="keyword" class="input" placeholder="输入规则、标题或文件路径" /></div>
        <div class="field"><label>风险等级</label><select v-model="severity" class="select"><option value="ALL">全部等级</option><option v-for="item in ['CRITICAL','HIGH','MEDIUM','LOW','INFO']" :key="item" :value="item">{{ statusLabel(item) }}</option></select></div>
        <div class="field"><label>变化状态</label><select v-model="state" class="select"><option value="ALL">全部状态</option><option v-for="item in ['NEW','EXISTING','FIXED','REOPENED']" :key="item" :value="item">{{ statusLabel(item) }}</option></select></div>
      </div>
      <div v-if="selectedRun" class="batch-context"><div><span>当前批次</span><strong>{{ batchNumber(selectedRun.id) }}</strong></div><div><span>扫描时间</span><strong>{{ formatTime(selectedRun.createdAt) }}</strong></div><div><span>扫描类型</span><strong>{{ modeLabel(selectedRun) }}</strong></div><div><span>批次状态</span><StatusBadge :value="selectedRun.status" /></div><div><span>台账统计</span><strong>{{ findings.length }} 当前 / {{ batchNewCount }} 新增 / {{ batchFixedCount }} 修复</strong></div></div>
      <div v-if="aiConfig.enabled && selectedRun" class="ai-batch-bar">
        <div><span class="eyebrow">工具辅助复核</span><p>工具根据规则命中位置与源码证据提供复核建议，之后由审核人员结合业务和调用链确认；工具建议不会覆盖人工结论。</p></div>
        <div v-if="aiJob" class="ai-job-progress"><strong>{{ aiJob.completedFindings + aiJob.failedFindings }} / {{ aiJob.totalFindings }}</strong><span>{{ aiJobStatusLabel(aiJob.status) }}<template v-if="aiJob.failedFindings"> · {{ aiJob.failedFindings }} 失败</template></span></div>
        <div class="ai-batch-actions"><button v-if="aiJob" class="button secondary" @click="openAiDetails">执行详情</button><button v-if="aiJob && ['PARTIAL','FAILED'].includes(aiJob.status) && aiJob.failedFindings" class="button secondary" :disabled="startingAiBatch" @click="startBatchAiReview(true)">重试失败项</button><button v-if="aiJob && ['PENDING','RUNNING'].includes(aiJob.status)" class="button danger" :disabled="stoppingAi" @click="stopAiReview">{{ stoppingAi ? '停止中…' : '停止工具审核' }}</button><button class="button primary" :disabled="startingAiBatch || aiJob?.status === 'RUNNING' || aiJob?.status === 'PENDING'" @click="startBatchAiReview(false)">{{ startingAiBatch ? '正在启动…' : '批量工具审核' }}</button></div>
      </div>
    </section>
    <section class="panel">
      <div v-if="loading" class="empty-state compact-empty"><h3>正在加载扫描批次</h3><p>正在读取 {{ selectedRunId ? batchNumber(selectedRunId) : '' }} 的问题明细。</p></div>
      <div v-else-if="filtered.length" class="table-scroll findings-table">
        <table class="data-table">
          <thead><tr><th>扫描批次</th><th>等级</th><th>问题</th><th>位置</th><th>扫描器 / 规则</th><th>变化</th><th>工具建议</th><th>人工结论</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in pagedFindings" :key="`${item.scanRunId}-${item.id}`">
              <td><RouterLink :to="`/scans/${item.scanRunId}`" class="batch-link">{{ batchNumber(item.scanRunId) }}</RouterLink></td>
              <td><StatusBadge :value="item.severity" /></td>
              <td><strong>{{ findingTitle(item) }}</strong><div v-if="findingTitle(item) !== item.title" class="muted finding-original" :title="item.title">原始描述：{{ item.title }}</div><div class="finding-tags"><span>{{ categoryLabel(item.category) }}</span><span v-if="item.cwe">弱点编号 {{ item.cwe }}</span></div><button v-if="item.codeSnippet" type="button" class="snippet-trigger" @click="snippetFinding = item"><span>{{ isRedacted(item) ? '◈' : '⌁' }}</span> {{ isRedacted(item) ? '查看脱敏说明' : '查看代码片段' }}</button></td>
              <td><span class="mono">{{ item.filePath }}:{{ item.startLine ?? '-' }}</span></td>
              <td><div>{{ scannerLabel(item.scanner) }}</div><div class="mono" style="margin-top:4px">{{ item.ruleId }}</div></td>
              <td><StatusBadge :value="item.resultStatus" /></td>
              <td><div v-if="aiReviews[item.id]" class="ai-review-cell"><strong>{{ aiVerdictLabel(aiReviews[item.id].consolidatedVerdict) }}</strong><span v-if="aiReviews[item.id].confidenceAvailable">可信度 {{ aiConfidence(aiReviews[item.id].confidence) }}</span><small v-if="aiReviews[item.id].errorMessage">{{ aiReviews[item.id].errorMessage }}</small><small v-if="aiReviews[item.id].disagreement">工具结果存在分歧，必须人工处理</small><button v-if="aiReviews[item.id].status !== 'FAILED'" type="button" class="snippet-trigger" @click="applyAiSuggestion(item)">带入人工复核</button><button v-else type="button" class="snippet-trigger" :disabled="aiReviewingId === item.id" @click="reviewWithAi(item)">{{ aiReviewingId === item.id ? '重试中…' : '重新审核' }}</button></div><button v-else-if="aiConfig.enabled" class="button secondary compact" :disabled="aiReviewingId === item.id" @click="reviewWithAi(item)">{{ aiReviewingId === item.id ? '审核中…' : '工具初审' }}</button><span v-else class="muted">未启用</span></td>
              <td><StatusBadge v-if="item.triageStatus" :value="item.triageStatus" /><span v-else class="muted">等待复核</span><div v-if="item.reviewer" class="mono finding-reviewer">复核人：{{ item.reviewer }}</div></td>
              <td><button class="button secondary compact" @click="openReview(item)">{{ item.triageStatus ? '更新复核' : '人工复核' }}</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!loading && filtered.length" class="finding-card-list">
        <article v-for="item in pagedFindings" :key="`mobile-${item.scanRunId}-${item.id}`" class="finding-card">
          <header><StatusBadge :value="item.severity" /><StatusBadge :value="item.resultStatus" /></header>
          <h3>{{ findingTitle(item) }}</h3>
          <p v-if="findingTitle(item) !== item.title" class="muted">{{ item.title }}</p>
          <code>{{ item.filePath }}:{{ item.startLine ?? '-' }}</code>
          <dl><div><dt>扫描器</dt><dd>{{ scannerLabel(item.scanner) }}</dd></div><div><dt>规则编号</dt><dd>{{ item.ruleId }}</dd></div><div><dt>工具建议</dt><dd>{{ aiVerdictLabel(aiReviews[item.id]?.consolidatedVerdict) }} / {{ aiConfidence(aiReviews[item.id]?.confidence) }}</dd></div><div><dt>人工结论</dt><dd><StatusBadge v-if="item.triageStatus" :value="item.triageStatus" /><span v-else>等待复核</span></dd></div></dl>
          <button v-if="aiConfig.enabled && !aiReviews[item.id]" class="button secondary" :disabled="aiReviewingId === item.id" @click="reviewWithAi(item)">{{ aiReviewingId === item.id ? '工具审核中…' : '工具初审' }}</button>
          <button v-if="aiReviews[item.id]" class="button secondary" @click="applyAiSuggestion(item)">将工具建议带入人工复核</button>
          <button class="button secondary" @click="openReview(item)">{{ item.triageStatus ? '更新复核' : '人工复核' }}</button>
        </article>
      </div>
      <footer v-if="!loading && filtered.length > pageSize" class="table-pager"><span>第 {{ page }} / {{ totalPages }} 页，共 {{ filtered.length }} 个问题</span><div><button class="button secondary compact" :disabled="page <= 1" @click="page--">上一页</button><button class="button secondary compact" :disabled="page >= totalPages" @click="page++">下一页</button></div></footer>
      <EmptyState v-if="!loading && !filtered.length" title="当前批次没有匹配的问题" :description="selectedRun ? `${batchNumber(selectedRun.id)} 没有符合当前筛选条件的问题；若刚完成扫描，请先执行 工具审核或人工复核。` : '尚无可查询的扫描批次。'" />
    </section>

    <Teleport to="body">
      <div v-if="aiDetailsOpen && aiJob" class="modal-backdrop ai-detail-backdrop" @click.self="aiDetailsOpen = false">
        <section class="ai-detail-dialog" role="dialog" aria-modal="true" aria-label="工具批量审核执行详情">
          <header class="ai-detail-head"><div><span class="eyebrow">工具审核执行详情</span><h2>{{ batchNumber(aiJob.scanRunId) }} 批量审核执行详情</h2><p>整批审核结束后自动补跑一次失败项；成功项保留，仍失败时可单独重试。</p></div><div class="ai-detail-head-actions"><button v-if="['PARTIAL','FAILED'].includes(aiJob.status) && aiJob.failedFindings" class="button secondary compact" :disabled="startingAiBatch" @click="startBatchAiReview(true)">重试失败项</button><button v-if="['PENDING','RUNNING'].includes(aiJob.status)" class="button danger compact" :disabled="stoppingAi" @click="stopAiReview">{{ stoppingAi ? '停止中…' : '停止工具审核' }}</button><button class="button secondary compact" :disabled="aiDetailsLoading" @click="loadAiJobRecords(aiJob.id)">{{ aiDetailsLoading ? '刷新中…' : '刷新' }}</button><button class="icon-button" aria-label="关闭 工具执行详情" @click="aiDetailsOpen = false">×</button></div></header>
          <div class="ai-detail-metrics">
            <div><span>任务编号</span><strong>#{{ aiJob.id }}</strong></div><div><span>执行方式</span><strong>工具复核</strong></div><div><span>任务状态</span><strong>{{ aiJobStatusLabel(aiJob.status) }}</strong></div><div><span>完成 / 失败</span><strong>{{ aiJob.completedFindings }} / {{ aiJob.failedFindings }}</strong></div><div><span>开始时间</span><strong>{{ formatTime(aiJob.startedAt ?? aiJob.createdAt) }}</strong></div>
          </div>
          <div class="ai-detail-progress"><div><span>整体进度</span><strong>{{ aiJob.completedFindings + aiJob.failedFindings }} / {{ aiJob.totalFindings }}</strong></div><div class="progress-meter"><span :style="{ width: `${aiJobProgress}%` }"></span></div><b>{{ aiJobProgress }}%</b></div>
          <div class="ai-review-flow" aria-label="工具检查执行步骤"><div><b>01</b><span>本地提取真实源码</span></div><i>→</i><div><b>02</b><span>工具初审</span></div><i>→</i><div><b>03</b><span>工具复审</span></div><i>→</i><div><b>04</b><span>生成工具建议</span></div></div>
          <div v-if="aiJob.errorMessage || aiDetailError" class="error-banner ai-detail-error">{{ aiDetailError || aiJob.errorMessage }}</div>
          <div class="ai-detail-table-wrap">
            <table class="data-table ai-detail-table">
              <thead><tr><th>问题</th><th>执行状态</th><th>工具初审</th><th>工具复审</th><th>失败信息</th></tr></thead>
              <tbody>
                <tr v-for="row in aiExecutionRows" :key="`ai-detail-${row.finding.id}`">
                  <td><strong>{{ findingTitle(row.finding) }}</strong><code>{{ row.finding.filePath }}:{{ row.finding.startLine ?? '-' }}</code></td>
                  <td><span class="ai-stage" :class="`is-${row.stage.toLowerCase()}`">{{ aiStageLabel(row.stage) }}</span></td>
                  <td><template v-if="row.primary"><b>{{ aiVerdictLabel(row.primary.verdict) }}</b><span>可信度 {{ aiConfidence(row.primary.confidence) }}</span><small v-if="row.primary.reason">{{ row.primary.reason }}</small></template><span v-else class="muted">等待执行</span></td>
                  <td><template v-if="row.verification"><b>{{ aiVerdictLabel(row.verification.verdict) }}</b><span>可信度 {{ aiConfidence(row.verification.confidence) }}</span><small v-if="row.verification.reason">{{ row.verification.reason }}</small></template><span v-else class="muted">—</span></td>
                  <td><span v-if="row.failure" class="danger-text">{{ row.failure.errorMessage || '工具服务调用失败' }}</span><span v-else class="muted">—</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>
    </Teleport>

    <Teleport to="body">
      <div v-if="selected" class="modal-backdrop" @click.self="selected = null">
        <section class="review-dialog" role="dialog" aria-modal="true" aria-label="人工复核">
          <header class="review-head"><div><span class="eyebrow">人工复核</span><h2>{{ findingTitle(selected) }}</h2></div><button class="icon-button" aria-label="关闭" @click="selected = null">×</button></header>
          <div class="review-context">
            <div><span>扫描批次</span><RouterLink :to="`/scans/${selected.scanRunId}`" class="batch-link">{{ batchNumber(selected.scanRunId) }}</RouterLink></div>
            <div><span>风险等级</span><StatusBadge :value="selected.severity" /></div>
            <div><span>扫描规则</span><b>{{ scannerLabel(selected.scanner) }} / {{ selected.ruleId }}</b></div>
            <div><span>代码位置</span><code>{{ selected.filePath }}:{{ selected.startLine ?? '-' }}</code></div>
            <div v-if="selected.codeSnippet" class="review-code"><span>{{ isRedacted(selected) ? '敏感内容' : '代码片段' }}</span><button type="button" class="snippet-trigger" @click="snippetFinding = selected"><span>{{ isRedacted(selected) ? '◈' : '⌁' }}</span> {{ isRedacted(selected) ? '查看脱敏说明' : '在代码查看器中打开' }}</button></div>
            <div v-if="selected.description"><span>问题说明</span><p>{{ selected.description }}</p></div>
            <div v-if="selected.recommendation"><span>整改建议</span><p>{{ selected.recommendation }}</p></div>
          </div>
          <form class="review-form" @submit.prevent="saveReview">
            <div class="field"><label for="triage-status">复核结论</label><select id="triage-status" v-model="review.triageStatus" class="select" required><option value="CONFIRMED">确认风险</option><option value="FALSE_POSITIVE">误报</option><option value="ACCEPTED_RISK">接受风险</option><option value="FIXED_PENDING">已整改，等待复扫验证</option></select></div>
            <div class="field"><label for="triage-reviewer">复核人</label><input id="triage-reviewer" v-model.trim="review.reviewer" class="input" maxlength="128" required /></div>
            <div class="field span-2"><label for="triage-comment">复核说明</label><textarea id="triage-comment" v-model="review.comment" class="textarea" maxlength="2000" placeholder="记录判断依据、整改建议或风险接受原因"></textarea></div>
            <div v-if="review.triageStatus === 'ACCEPTED_RISK'" class="field span-2"><label for="triage-expires">风险接受到期时间（可选）</label><input id="triage-expires" v-model="review.expiresAt" type="datetime-local" class="input" /></div>
            <div class="form-actions span-2"><button type="button" class="button secondary" @click="selected = null">取消</button><button class="button primary" :disabled="saving">{{ saving ? '正在保存…' : '保存复核结论' }}</button></div>
          </form>
        </section>
      </div>
    </Teleport>

    <Teleport to="body">
      <div v-if="snippetFinding" class="modal-backdrop snippet-backdrop" @click.self="snippetFinding = null">
        <section class="snippet-dialog" role="dialog" aria-modal="true" aria-label="代码片段查看器">
          <header class="snippet-head">
            <div><span class="eyebrow">{{ isRedacted(snippetFinding) ? '敏感信息保护' : '源码上下文' }}</span><h2>{{ findingTitle(snippetFinding) }}</h2></div>
            <button class="icon-button" aria-label="关闭代码片段" @click="snippetFinding = null">×</button>
          </header>
          <div class="snippet-meta">
            <div><span>扫描批次</span><RouterLink :to="`/scans/${snippetFinding.scanRunId}`" class="batch-link">{{ batchNumber(snippetFinding.scanRunId) }}</RouterLink></div>
            <div><span>扫描器 / 规则</span><strong>{{ scannerLabel(snippetFinding.scanner) }} / {{ snippetFinding.ruleId }}</strong></div>
            <div class="snippet-path"><span>文件位置</span><code>{{ snippetFinding.filePath }}:{{ snippetFinding.startLine ?? '-' }}</code></div>
          </div>
          <template v-if="isRedacted(snippetFinding)">
            <div class="redacted-view">
              <div class="redacted-mark"><span>!</span></div>
              <div class="redacted-copy"><span>历史内容已脱敏</span><h3>敏感内容已脱敏</h3><p>该位置检测到疑似密钥、令牌或其他敏感凭据。为避免在页面、数据库和整改报告中造成二次泄露，CodeSentry 不保存也不展示原始内容。</p></div>
              <div class="redacted-location"><span>请在本地源文件核查</span><code>{{ snippetFinding.filePath }}:{{ snippetFinding.startLine ?? '-' }}</code><small>确认后请删除硬编码凭据，并及时轮换可能已经暴露的密钥。</small></div>
            </div>
          </template>
          <template v-else>
            <div class="code-viewer-head"><span>代码预览</span><span>保留原始缩进 · 横向滚动查看长代码</span></div>
            <pre class="code-viewer"><code>{{ snippetFinding.codeSnippet }}</code></pre>
          </template>
        </section>
      </div>
    </Teleport>
  </div>
</template>
