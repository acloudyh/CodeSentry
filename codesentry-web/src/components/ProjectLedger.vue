<script setup lang="ts">
import { ref, watch, computed, onMounted, onBeforeUnmount } from 'vue'
import { api } from '@/api/codesentry'
import type { LedgerFinding, ProjectScanRound, TriageStatus, AiReviewJob } from '@/types'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import AppModal from '@/components/ui/AppModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { findingTitle, projectRoundLabel, projectRoundScope } from '@/utils/labels'
const emit = defineEmits<{ changed: []; openAsset: [runId: number] }>()
const props = defineProps<{ businessProjectId: number; roundId?: number }>()
const rows = ref<LedgerFinding[]>([]), rounds = ref<ProjectScanRound[]>([])
const roundId = ref<number | undefined>(props.roundId), assetId = ref(''), severity = ref('ALL'), triage = ref('ALL'), reportState = ref('ALL'), keyword = ref('')
const roundPage = ref(1), roundTotal = ref(0)
const page = ref(1), total = ref(0), pageSize = ref(20), busy = ref(false), error = ref('')
const summary = ref({ total: 0, newCount: 0, fixedCount: 0 })
const scopeLabel = computed(() => assetId.value ? current.value?.items.find(a => String(a.assetId) === assetId.value)?.assetName ?? '所选资产' : current.value?.assetHistory ? current.value.items[0]?.assetName ?? '历史资产' : '全部资产')
const selected = ref<LedgerFinding | null>(null), saving = ref(false), reviewStatus = ref<TriageStatus>('CONFIRMED'), comment = ref('')
const current = computed(() => rounds.value.find(r => r.id === roundId.value))
const reportLabels = { INCLUDED: '已采纳', EXCLUDED: '未采纳', PENDING: '待审核' }
const checked = ref<LedgerFinding[]>([]), batchOpen = ref(false), batchBusy = ref(false), batchMessage = ref('')
const batchStatus = ref<TriageStatus>('CONFIRMED'), batchComment = ref(''), reviewer = ref(localStorage.getItem('codesentry-reviewer') ?? '本地复核人'), expiresAt = ref('')
const toolsOpen = ref(false), toolsBusy = ref(false), toolJobs = ref<AiReviewJob[]>([]), toolErrors = ref<string[]>([])
const toolScope = computed(() => (current.value?.items ?? []).filter(a => (!assetId.value || String(a.assetId) === assetId.value) && a.runId && ['COMPLETED','PARTIAL'].includes(a.status)))
const allChecked = computed(() => rows.value.length > 0 && rows.value.every(f => checked.value.some(c => c.id === f.id)))
function toggle(f: LedgerFinding) { checked.value = checked.value.some(c => c.id === f.id) ? checked.value.filter(c => c.id !== f.id) : [...checked.value, f] }
function togglePage() { checked.value = allChecked.value ? [] : [...rows.value] }
function openBatch() { batchStatus.value = 'CONFIRMED'; batchComment.value = ''; expiresAt.value = ''; batchMessage.value = ''; batchOpen.value = true }
async function saveBatch() {
  if (!checked.value.length || batchBusy.value) return
  batchBusy.value = true; error.value = ''; batchMessage.value = ''
  const groups = new Map<number, LedgerFinding[]>()
  checked.value.forEach(f => groups.set(f.scanRunId, [...(groups.get(f.scanRunId) ?? []), f]))
  let completed = 0
  try {
    for (const [runId, findings] of groups) {
      const result = await api.batchTriageFindings(runId, { triageStatus: batchStatus.value, comment: batchComment.value, reviewer: reviewer.value, expiresAt: expiresAt.value || undefined, selectors: findings.map(f => ({ findingId: f.id })) })
      completed += result.matched
      checked.value = checked.value.filter(f => !findings.some(done => done.id === f.id))
    }
    batchOpen.value = false
    localStorage.setItem('codesentry-reviewer', reviewer.value)
  } catch(e) { error.value = `已处理 ${completed} 条，其余保留勾选，可重试：${(e as Error).message}` }
  finally { const failure = error.value; batchMessage.value = `已完成 ${completed} 条人工复核`; batchBusy.value = false; await load(); if(failure) error.value = failure; emit('changed') }
}
async function startTools() {
  if (toolsBusy.value) return
  toolsBusy.value = true; toolErrors.value = []
  try {
    for (const a of toolScope.value) {
      if (toolJobs.value.some(j => j.scanRunId === a.runId)) continue
      try { const job = await api.aiReviewRun(a.runId!); toolJobs.value = [...toolJobs.value.filter(j => j.scanRunId !== job.scanRunId), job] }
      catch(e) { toolErrors.value.push(`${a.assetName}：${(e as Error).message}`) }
    }
  } finally { toolsBusy.value = false }
}
async function retryTool(job: AiReviewJob) {
  try { const next = await api.aiRetryFailedRun(job.scanRunId); toolJobs.value = toolJobs.value.map(j => j.id === job.id ? next : j) } catch(e) { toolErrors.value.push((e as Error).message) }
}
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { timer = setInterval(async () => {
  const active = toolJobs.value.filter(j => ['PENDING','RUNNING'].includes(j.status))
  if (!active.length) return
  try { for (const job of active) { const next = await api.aiReviewJob(job.id); toolJobs.value = toolJobs.value.map(j => j.id === next.id ? next : j) }; await load(); emit('changed') } catch { /* next poll retries */ }
}, 5000) })
onBeforeUnmount(() => { if(timer) clearInterval(timer) })
watch([roundId, assetId, severity, triage, reportState, keyword, page, pageSize], () => { checked.value = [] })
watch(() => props.businessProjectId, () => { checked.value = []; toolJobs.value = []; toolsOpen.value = false; batchOpen.value = false })
let generation = 0
async function load() {
  const token = ++generation; busy.value = true; error.value = ''
  const params = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize.value), severity: severity.value, triage: triage.value, reportState: reportState.value, keyword: keyword.value })
  if(roundId.value) params.set('roundId', String(roundId.value))
  if(assetId.value) params.set('assetId', assetId.value)
  try { const data = await api.projectFindings(props.businessProjectId, params); if(token === generation) { rows.value = data.items; total.value = data.total; summary.value = data.summary ?? { total: 0, newCount: 0, fixedCount: 0 } } } catch(e) { if(token === generation) error.value = (e as Error).message } finally { if(token === generation) busy.value = false }
}
async function initialize() {
  try {
    const data = await api.projectRounds(props.businessProjectId, roundPage.value)
    roundTotal.value = data.total
    rounds.value = data.items
    roundId.value = props.roundId ?? data.items[0]?.id
    if(props.roundId && !rounds.value.some(r => r.id === props.roundId)) rounds.value.unshift(await api.projectRound(props.businessProjectId, props.roundId))
    page.value = 1; assetId.value = ''; await load()
  } catch(e) { error.value = (e as Error).message }
}
async function changeRoundPage(delta: number) {
  roundPage.value += delta
  try {
    const data = await api.projectRounds(props.businessProjectId, roundPage.value)
    rounds.value = data.items; roundTotal.value = data.total; roundId.value = data.items[0]?.id; assetId.value = ''; page.value = 1
    await load()
  } catch(e) { error.value = (e as Error).message }
}
function open(item: LedgerFinding) { selected.value = item; reviewStatus.value = (item.triageStatus as TriageStatus) ?? 'CONFIRMED'; comment.value = item.triageComment ?? '' }
async function save() {
  if(!selected.value) return; saving.value = true
  try { await api.triageFinding(selected.value.id, { triageStatus: reviewStatus.value, comment: comment.value, reviewer: localStorage.getItem('codesentry-reviewer') ?? '本地复核人' }); selected.value = null; await load(); emit('changed') } catch(e) { error.value = (e as Error).message } finally { saving.value = false }
}
watch(() => [props.businessProjectId, props.roundId], () => { roundPage.value = 1; void initialize() }, { immediate: true })
watch(roundId, () => { assetId.value = '' })
watch([roundId, assetId, severity, triage, reportState, pageSize], () => { page.value = 1; void load() })
watch(page, load)
</script>
<template>
  <div class="page-stack">
    <div class="notice" v-if="current">{{ projectRoundLabel(current) }} · {{ projectRoundScope(current) }} · 覆盖 {{ current.items.length }}/{{ current.assetCount }} 个资产；仅显示本轮已完成或部分完成的扫描结果，失败项不使用旧结果补齐。</div>
    <div class="row-actions" v-if="roundTotal > 20"><span>扫描历史 · 第 {{ roundPage }} 页 / 共 {{ roundTotal }} 轮</span><button class="button secondary compact" :disabled="roundPage <= 1 || busy" @click="changeRoundPage(-1)">更近轮次</button><button class="button secondary compact" :disabled="roundPage*20 >= roundTotal || busy" @click="changeRoundPage(1)">更早轮次</button></div>
    <form class="panel ledger-filters" @submit.prevent="page = 1; load()">
      <div class="field"><label>扫描记录 / 轮次</label><select v-model="roundId" class="select"><option v-for="r in rounds" :key="r.id" :value="r.id">{{ projectRoundLabel(r) }} · {{ projectRoundScope(r) }} · {{ r.createdAt.replace('T',' ') }}</option></select></div>
      <div class="field"><label>扫描资产</label><select class="select" v-model="assetId"><option value="">全部资产</option><option v-for="a in current?.items ?? []" :key="a.assetId" :value="String(a.assetId)">{{ a.assetName }}</option></select></div>
      <div class="field"><label>严重等级</label><select v-model="severity" class="select"><option value="ALL">全部等级</option><option value="CRITICAL">严重</option><option value="HIGH">高危</option><option value="MEDIUM">中危</option><option value="LOW">低危</option><option value="INFO">提示</option></select></div>
      <div class="field"><label>人工复核</label><select v-model="triage" class="select"><option value="ALL">全部结论</option><option value="PENDING">待人工复核</option><option value="CONFIRMED">确认风险</option><option value="FALSE_POSITIVE">误报</option><option value="ACCEPTED_RISK">接受风险</option><option value="FIXED_PENDING">已整改待验证</option></select></div>
      <div class="field"><label>报告采纳</label><select v-model="reportState" class="select"><option value="ALL">全部状态</option><option value="INCLUDED">已采纳</option><option value="EXCLUDED">未采纳</option><option value="PENDING">待审核</option></select></div>
      <div class="field"><label>关键字</label><div class="search-row"><input v-model="keyword" class="input" placeholder="问题、规则、位置或资产" /><button class="button secondary" :disabled="busy">查询</button></div></div>
    </form>
    <section class="panel ledger-summary" aria-label="项目问题统计" :aria-busy="busy"><div class="summary-heading"><strong>问题统计</strong><span class="muted">{{ current ? projectRoundLabel(current) : '暂无扫描轮次' }} · {{ scopeLabel }} · 按问题条数统计</span></div><dl class="summary-grid"><div><dt>{{ current?.assetHistory ? '历史扫描问题' : '本轮问题' }}</dt><dd>{{ busy ? '…' : summary.total }}<small> 条</small></dd></div><div><dt>新增 / 重新打开</dt><dd>{{ busy ? '…' : summary.newCount }}<small> 条</small></dd></div><div><dt>已修复</dt><dd>{{ busy ? '…' : summary.fixedCount }}<small> 条</small></dd></div><div><dt>筛选结果</dt><dd>{{ busy ? '…' : total }}<small> 条</small></dd></div></dl><p class="muted summary-note">本轮统计随轮次和资产切换；筛选结果对应当前全部筛选条件，包含所有分页。</p></section>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <div class="panel batch-toolbar"><span>已选 {{ checked.length }} 条（当前页）</span><button class="button secondary compact" :disabled="!toolScope.length || toolsBusy" @click="toolsOpen = true">批量工具审核</button><button class="button primary compact" :disabled="!checked.length || batchBusy" @click="openBatch">批量人工复核</button><span v-if="batchMessage">{{ batchMessage }}</span></div>
    <section class="panel"><div v-if="busy" class="notice">正在加载项目问题…</div><div v-else-if="rows.length" class="table-scroll"><table class="data-table"><thead><tr><th><input type="checkbox" aria-label="选择本页全部问题" :checked="allChecked" @change="togglePage" /></th><th>问题 / 来源扫描</th><th>所属资产</th><th>等级</th><th>问题与位置</th><th>人工结论</th><th>报告采纳</th><th>操作</th></tr></thead><tbody><tr v-for="f in rows" :key="`${f.scanRunId}-${f.id}`"><td><input type="checkbox" :aria-label="`选择问题 F-${f.id}`" :checked="checked.some(c => c.id === f.id)" @change="toggle(f)" /></td><td><span class="mono">F-{{ f.id }}</span><div><RouterLink :to="`/scans/${f.scanRunId}`">扫描 #{{ f.scanRunId }}</RouterLink></div></td><td>{{ f.assetName }}</td><td><StatusBadge :value="f.severity" /></td><td><strong>{{ findingTitle(f) }}</strong><div class="mono muted location">{{ f.filePath }}{{ f.startLine ? `:${f.startLine}` : '' }}</div></td><td><StatusBadge v-if="f.triageStatus" :value="f.triageStatus" /><span v-else>待复核</span></td><td>{{ reportLabels[f.reportState] }}</td><td><div class="row-actions"><button class="row-action-link" @click="open(f)">详情 / 复核</button><RouterLink class="row-action-link" :to="`/findings?runId=${f.scanRunId}`" @click="emit('openAsset', f.scanRunId)">工具审核</RouterLink></div></td></tr></tbody></table></div><EmptyState v-else title="没有匹配的问题" description="可调整筛选条件；未扫描或失败的资产不会显示历史结果。" /><footer class="table-pager"><span>共 {{ total }} 条 · 第 {{ page }} / {{ Math.max(1, Math.ceil(total/pageSize)) }} 页</span><div class="row-actions"><select class="select" v-model.number="pageSize" aria-label="每页条数"><option :value="20">20 条/页</option><option :value="50">50 条/页</option><option :value="100">100 条/页</option></select><button class="button secondary compact" :disabled="page <= 1 || busy" @click="page--">上一页</button><button class="button secondary compact" :disabled="page*pageSize >= total || busy" @click="page++">下一页</button></div></footer></section>
    <AppModal :open="batchOpen" title="批量人工复核" :busy="batchBusy" @close="batchOpen = false"><form class="page-stack" @submit.prevent="saveBatch"><p>统一处理已选 {{ checked.length }} 条问题，按来源扫描分别保存。</p><div class="field"><label>复核结论</label><select class="select" v-model="batchStatus"><option value="CONFIRMED">确认风险</option><option value="FALSE_POSITIVE">误报</option><option value="ACCEPTED_RISK">接受风险</option><option value="FIXED_PENDING">已整改待验证</option></select></div><div class="field"><label>复核人</label><input v-model.trim="reviewer" class="input" required maxlength="128" /></div><div class="field"><label>统一复核说明</label><textarea v-model="batchComment" class="input" maxlength="2000" /></div><div v-if="batchStatus === 'ACCEPTED_RISK'" class="field"><label>风险接受到期时间（可选）</label><input type="datetime-local" class="input" v-model="expiresAt" /></div><div v-if="error" class="error-banner">{{ error }}</div><button class="button primary" :disabled="batchBusy || !checked.length">{{ batchBusy ? '正在批量保存…' : `保存 ${checked.length} 条复核结论` }}</button></form></AppModal>
    <AppModal :open="toolsOpen" title="批量工具审核" :busy="toolsBusy" @close="toolsOpen = false"><div class="page-stack"><p>审核当前轮次、所选资产的 {{ toolScope.length }} 个来源扫描批次中的全部问题。严重等级、关键字及问题勾选不限制工具审核范围。依赖漏洞沿用原有合并审核规则。</p><div v-for="a in toolScope" :key="a.assetId">{{ a.assetName }} · 扫描 #{{ a.runId }}</div><button class="button primary" :disabled="toolsBusy || !toolScope.length || toolJobs.some(j => ['PENDING','RUNNING'].includes(j.status))" @click="startTools">{{ toolsBusy ? '正在提交…' : '启动批量工具审核' }}</button><div v-for="message in toolErrors" :key="message" class="error-banner">{{ message }}</div><div v-for="job in toolJobs" :key="job.id" class="panel tool-job"><span>扫描 #{{ job.scanRunId }} · <StatusBadge :value="job.status" /> · 已处理 {{ job.completedFindings }}/{{ job.totalFindings }}</span><RouterLink :to="`/findings?runId=${job.scanRunId}`" @click="emit('openAsset', job.scanRunId)">查看执行详情</RouterLink><button v-if="['PARTIAL','FAILED'].includes(job.status)" class="button secondary compact" @click="retryTool(job)">重试失败项</button></div></div></AppModal>
    <AppModal :open="!!selected" title="问题详情与人工复核" :busy="saving" @close="selected = null"><form v-if="selected" class="page-stack" @submit.prevent="save"><h3>F-{{ selected.id }} · {{ findingTitle(selected) }}</h3><p>{{ selected.assetName }} · {{ selected.filePath }}{{ selected.startLine ? `:${selected.startLine}` : '' }}</p><p class="muted">扫描 #{{ selected.scanRunId }} · {{ selected.scanner }} / {{ selected.ruleId }}</p><p v-if="selected.codeSnippet?.trim() === '[REDACTED]'" class="notice">未能读取原始代码：扫描快照可能已清理、文件过大或无法作为文本预览。请根据上述文件位置核验。</p><pre v-else class="source">{{ selected.codeSnippet || selected.description || '扫描器未提供原始片段，请根据文件位置核验。' }}</pre><p>{{ selected.recommendation }}</p><div class="field"><label>人工结论</label><select class="select" v-model="reviewStatus"><option value="CONFIRMED">确认风险</option><option value="FALSE_POSITIVE">误报</option><option value="ACCEPTED_RISK">接受风险</option><option value="FIXED_PENDING">已整改待验证</option></select></div><div class="field"><label>复核说明</label><textarea v-model="comment" class="input" rows="3" /></div><div v-if="error" class="error-banner">{{ error }}</div><button class="button primary" :disabled="saving">{{ saving ? '保存中…' : '保存复核结论' }}</button></form></AppModal>
  </div>
</template>
<style scoped>.ledger-summary{padding:20px}.summary-heading{display:flex;align-items:center;flex-wrap:wrap;gap:12px}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));margin:18px 0 12px;gap:16px}.summary-grid dt{color:var(--text-secondary);font-size:13px}.summary-grid dd{margin:8px 0 0;font-size:24px;font-weight:700}.summary-grid small{font-size:13px;font-weight:400}.summary-note{font-size:12px;margin:0}@media(max-width:600px){.summary-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}.batch-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:16px}.tool-job{display:flex;gap:12px;flex-wrap:wrap;padding:12px}.ledger-filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;padding:20px}.search-row{display:flex;gap:8px}.search-row .button{flex-shrink:0;white-space:nowrap}.location{max-width:350px;overflow-wrap:anywhere;margin-top:6px}.source{white-space:pre-wrap;overflow-wrap:anywhere;max-height:280px;overflow:auto;background:var(--code-bg);padding:14px}.table-pager{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:16px}.table-pager .select{width:auto}.table-pager .row-actions{flex-wrap:wrap}@media(max-width:850px){.ledger-filters{grid-template-columns:1fr}.table-pager{flex-wrap:wrap}}</style>
