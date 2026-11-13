<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { BusinessProject } from '@/types'
import { api } from '@/api/codesentry'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import VerificationStatusBadge from '@/components/ui/VerificationStatusBadge.vue'
import ActionDropdown from '@/components/ui/ActionDropdown.vue'
import type { RemediationReportSummary, ScanProject } from '@/types'
import { formatChinaTime } from '@/utils/datetime'

const businessProjects = ref<BusinessProject[]>([])
const route = useRoute()
const businessProjectId = ref(String(route.query.businessProjectId ?? ''))
const assetId = ref('')
const loading = ref(false)
const page = ref(1), pageSize = ref(20), total = ref(0)
const pageSizes = [10, 20, 50, 100]
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
let requestId = 0
const filteredAssets = computed(() => projects.value.filter(a => !businessProjectId.value || String(a.businessProjectId) === businessProjectId.value))
const businessName = (assetId: number) => { const owner = projects.value.find(a => a.id === assetId)?.businessProjectId; return businessProjects.value.find(p => p.id === owner)?.name ?? '未归属项目' }
const router = useRouter()
const runs = ref<RemediationReportSummary[]>([])
const projects = ref<ScanProject[]>([])
const error = ref('')
const projectName = (id: number) => projects.value.find((item) => item.id === id)?.displayName ?? projects.value.find((item) => item.id === id)?.projectName ?? `资产-${id}`
const exporting = ref('')
const verifying = ref<number | null>(null), message = ref('')
const onlineBusy = (run: RemediationReportSummary) => ['PENDING','RUNNING'].includes(run.remediationStatus ?? '')
/** 提交独立后台核实任务；不启动扫描器，也不改变人工审核结论。 */
async function verifyOnline(run: RemediationReportSummary) {
  if(onlineBusy(run) || verifying.value !== null) return
  verifying.value = run.id; error.value = ''
  try { const updated = await api.verifyReportOnline(run.id); run.remediationStatus=updated.remediationStatus;run.remediationMessage=updated.remediationMessage;message.value=`报告 CSR-${String(run.id).padStart(6,'0')} 已提交后台补查，无需重新扫描代码或 JAR。`;await loadReports() }
  catch(e) { error.value=(e as Error).message }
  finally { verifying.value=null }
}
// 仅有可见的后台补查任务时轮询，离开页面即释放定时器，避免空闲列表持续请求。
let poll: ReturnType<typeof setInterval> | undefined
onMounted(() => { poll=setInterval(() => { if(!loading.value && runs.value.some(onlineBusy)) void loadReports() },5000) })
onBeforeUnmount(() => { if(poll) clearInterval(poll) })
/** 在线打开或下载指定格式的单资产报告，下载名使用资产显示名称。 */
async function exportReport(runId: number, format: 'html' | 'docx' | 'pdf', projectId: number) {
  if (format === 'html') {
    window.open(api.reportUrl(runId, format), '_blank', 'noopener,noreferrer')
    return
  }
  const key = `${runId}-${format}`
  exporting.value = key; error.value = ''
  try {
    const response = await fetch(api.reportUrl(runId, format))
    if (!response.ok) {
      const payload = await response.json().catch(() => null) as { message?: string } | null
      throw new Error(payload?.message ?? `${format.toUpperCase()} 报告生成失败`)
    }
    const objectUrl = URL.createObjectURL(await response.blob())
    const link = document.createElement('a')
    const safeName = projectName(projectId).replace(/[\\/:*?"<>|\x00-\x1f\x7f]/g, '_').trim()
    const projectType = projects.value.find(item => item.id === projectId)?.sourceType === 'JAR_UPLOAD' ? 'jar包' : '代码'
    link.href = objectUrl; link.download = `${safeName}${projectType}安全扫描.${format}`
    document.body.appendChild(link); link.click(); link.remove()
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '报告下载失败'
  } finally {
    exporting.value = ''
  }
}

/** 分页请求保留当前条件；序号拦截旧响应，防止快速切换项目后显示上一次查询。 */
async function loadReports() {
  const token = ++requestId; loading.value = true; error.value = ''
  try {
    const result = await api.reportPage(page.value, pageSize.value, Number(businessProjectId.value) || undefined, Number(assetId.value) || undefined)
    if (token === requestId) { runs.value = result.items; total.value = result.total; page.value = result.page }
  }
  catch(reason) { if(token === requestId) { runs.value = []; total.value = 0; error.value = reason instanceof Error ? reason.message : '报告列表加载失败' } }
  finally { if(token === requestId) loading.value = false }
}
/** 显式查询和筛选变化均从第一页开始，后台补查轮询则保留当前页。 */
function queryReports() { page.value = 1; void loadReports() }
function changePage(value: number) { page.value = Math.max(1, Math.min(totalPages.value, value)); void loadReports() }
/** 重置项目与资产；条件本就为空时仍刷新第一页。 */
function resetFilters() {
  if (!businessProjectId.value && !assetId.value) { queryReports(); return }
  businessProjectId.value = ''; assetId.value = ''
}
watch(businessProjectId, () => { assetId.value = '' }, { flush: 'sync' })
watch([businessProjectId, assetId, pageSize], queryReports)
onMounted(async () => {
  try {
    [businessProjects.value,projects.value] = await Promise.all([api.businessProjects(),api.projects()])
    if(businessProjectId.value && !businessProjects.value.some(p => String(p.id) === businessProjectId.value)) businessProjectId.value = ''
    await loadReports()
  } catch(reason) { error.value = reason instanceof Error ? reason.message : '项目与资产列表加载失败' }
})
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>整改报告</h2><p>保留人工确认的风险，以及工具复核完成、通过可信度门槛且未判为误报的问题。按台账条数对账；纳入整改的问题按依赖合并展示，并保留组内问题编号。</p></div><span class="section-code">报告列表</span></section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="message" class="notice" role="status">{{ message }}</div>
    <section class="panel report-filters" aria-label="报告条件查询"><div class="field"><label for="report-project">业务项目</label><select id="report-project" v-model="businessProjectId" class="select"><option value="">全部项目</option><option v-for="p in businessProjects" :key="p.id" :value="String(p.id)">{{ p.name }}</option></select></div><div class="field"><label for="report-asset">扫描资产</label><select id="report-asset" v-model="assetId" class="select"><option value="">全部资产</option><option v-for="a in filteredAssets" :key="a.id" :value="String(a.id)">{{ a.displayName || a.projectName }} · {{ a.sourceType === 'JAR_UPLOAD' ? 'JAR' : '代码' }}</option></select></div><div class="report-actions"><button class="button secondary" :disabled="loading" @click="queryReports">查询</button><button class="button secondary" @click="resetFilters">重置</button><button class="button primary" :disabled="!businessProjectId" @click="router.push(`/business-projects/${businessProjectId}?tab=reports`)">查看项目报告</button></div><small class="muted filter-note">{{ loading ? '正在查询报告…' : `共 ${total} 份报告` }}；资产选项随项目联动，项目汇总报告从“查看项目报告”进入。</small></section>
    <section class="panel">
      <div v-if="loading" class="notice">正在查询报告…</div>
      <div v-else-if="runs.length" class="table-scroll">
        <table class="data-table reports-table">
          <colgroup><col style="width:130px" /><col /><col style="width:130px" /><col style="width:190px" /><col style="width:240px" /><col style="width:100px" /><col style="width:125px" /><col style="width:400px" /></colgroup>
          <thead><tr><th>报告编号</th><th>扫描资产</th><th>扫描类型</th><th>扫描时间</th><th>统计</th><th>扫描状态</th><th>联网核实状态</th><th class="actions-heading">查看 / 导出</th></tr></thead>
          <tbody><tr v-for="run in runs" :key="run.id"><td class="mono">CSR-{{ String(run.id).padStart(6,'0') }}</td><td><strong>{{ projectName(run.projectId) }}</strong><div class="report-count-detail">{{ businessName(run.projectId) }}</div></td><td>{{ run.scanMode === 'FULL' ? '全量整改报告' : '增量整改报告' }}</td><td class="mono scan-timestamp">{{ formatChinaTime(run.createdAt) }}</td><td><b>{{ run.ledgerFindings }} 条台账问题</b><div class="report-count-detail">{{ run.totalFindings }} 条纳入整改 / {{ run.notIncludedFindings }} 条未纳入</div><div class="report-count-detail">合并整改 {{ run.detailGroups }} 组 · 纳入新增 {{ run.newFindings }} 条</div></td><td><StatusBadge :value="run.status" /></td><td><VerificationStatusBadge :network-mode="run.networkMode" :status="run.remediationStatus" :message="run.remediationMessage" /></td><td class="actions-cell"><div class="row-actions">
                <button type="button" class="button primary compact" :disabled="Boolean(exporting) || onlineBusy(run)" @click="exportReport(run.id, 'html', run.projectId)">在线查看</button>
                <ActionDropdown :label="exporting.startsWith(`${run.id}-`) ? '生成中…' : '导出'" :disabled="Boolean(exporting) || onlineBusy(run)" :items="[{ label: 'Word 文档', value: 'docx' }, { label: 'PDF 文档', value: 'pdf' }]" @select="exportReport(run.id, $event as 'docx' | 'pdf', run.projectId)" />
                <button v-if="run.networkMode === 'ONLINE'" type="button" class="row-action-link" :disabled="onlineBusy(run) || verifying !== null" :title="run.remediationMessage" @click="verifyOnline(run)">{{ onlineBusy(run) ? '正在补查…' : '补充联网核实' }}</button>
                <RouterLink :to="`/reports/${run.id}/acceptance`" class="row-action-link">整改验收</RouterLink>
              </div></td></tr></tbody>
        </table>
      </div>
      <EmptyState v-else title="没有匹配的整改报告" description="可调整项目或资产条件，或在扫描资产中发起扫描。" action="管理扫描资产" to="/projects" />
      <footer v-if="total > 0" class="table-pager" aria-label="整改报告分页">
        <span>第 {{ page }} / {{ totalPages }} 页，共 {{ total }} 份报告</span>
        <div class="pager-controls">
          <label class="pager-field">每页<select v-model.number="pageSize" class="pager-select" aria-label="报告每页条数" :disabled="loading"><option v-for="size in pageSizes" :key="size" :value="size">{{ size }} 条</option></select></label>
          <label class="pager-field">跳至<input :value="page" type="number" min="1" :max="totalPages" class="pager-select report-page-number" aria-label="报告页码" :disabled="loading" @change="changePage(Number(($event.target as HTMLInputElement).value) || 1)" /></label>
          <div class="pager-buttons"><button class="button secondary compact" :disabled="loading || page <= 1" @click="changePage(page - 1)">上一页</button><button class="button secondary compact" :disabled="loading || page >= totalPages" @click="changePage(page + 1)">下一页</button></div>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.report-filters{display:grid;grid-template-columns:minmax(180px,1fr) minmax(180px,1fr) auto;gap:16px;padding:20px;align-items:end}.filter-note{grid-column:1/-1}.report-filters .report-actions{flex-wrap:wrap}@media(max-width:850px){.report-filters{grid-template-columns:1fr}.filter-note{grid-column:auto}}
.reports-table { min-width: 1540px; }
.report-page-number { width: 75px; }
.actions-heading { text-align: right; }
.report-count-detail { margin-top: 0.35rem; color: var(--muted); font-size: 0.8rem; line-height: 1.5; }
</style>
