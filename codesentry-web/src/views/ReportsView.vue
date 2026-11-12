<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { BusinessProject } from '@/types'
import { api } from '@/api/codesentry'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import ActionDropdown from '@/components/ui/ActionDropdown.vue'
import type { RemediationReportSummary, ScanProject } from '@/types'
import { formatChinaTime } from '@/utils/datetime'

const businessProjects = ref<BusinessProject[]>([])
const businessProjectId = ref('')
const router = useRouter()
const runs = ref<RemediationReportSummary[]>([])
const projects = ref<ScanProject[]>([])
const error = ref('')
const projectName = (id: number) => projects.value.find((item) => item.id === id)?.projectName ?? `项目-${id}`
const exporting = ref('')
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

onMounted(async () => {
  try { businessProjects.value = await api.businessProjects() } catch (reason) { error.value = reason instanceof Error ? reason.message : '项目列表加载失败' }
  try {
    const [allRuns, allProjects] = await Promise.all([api.reports(100), api.projects()])
    runs.value = allRuns
    projects.value = allProjects
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '报告列表加载失败' }
})
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>整改报告</h2><p>保留人工确认的风险，以及工具复核完成、通过可信度门槛且未判为误报的问题。按台账条数对账；纳入整改的问题按依赖合并展示，并保留组内问题编号。</p></div><span class="section-code">报告列表</span></section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <section class="panel" style="padding:20px"><div class="field"><label for="report-project">项目汇总报告</label><div class="report-actions"><select id="report-project" v-model="businessProjectId" class="select"><option value="">选择业务项目</option><option v-for="p in businessProjects" :key="p.id" :value="String(p.id)">{{ p.name }}</option></select><button class="button primary" :disabled="!businessProjectId" @click="router.push(`/business-projects/${businessProjectId}?tab=reports`)">查看项目报告</button></div><small>以下保留单资产报告；项目汇总报告按项目扫描轮次导出。</small></div></section>
    <section class="panel">
      <div v-if="runs.length" class="table-scroll">
        <table class="data-table reports-table">
          <colgroup><col style="width:130px" /><col /><col style="width:130px" /><col style="width:155px" /><col style="width:240px" /><col style="width:84px" /><col style="width:286px" /></colgroup>
          <thead><tr><th>报告编号</th><th>扫描资产</th><th>扫描类型</th><th>扫描时间</th><th>统计</th><th>状态</th><th class="actions-heading">查看 / 导出</th></tr></thead>
          <tbody><tr v-for="run in runs" :key="run.id"><td class="mono">CSR-{{ String(run.id).padStart(6,'0') }}</td><td>{{ projectName(run.projectId) }}</td><td>{{ run.scanMode === 'FULL' ? '全量整改报告' : '增量整改报告' }}</td><td class="mono">{{ formatChinaTime(run.createdAt) }}</td><td><b>{{ run.ledgerFindings }} 条台账问题</b><div class="report-count-detail">{{ run.totalFindings }} 条纳入整改 / {{ run.notIncludedFindings }} 条未纳入</div><div class="report-count-detail">合并整改 {{ run.detailGroups }} 组 · 纳入新增 {{ run.newFindings }} 条</div></td><td><StatusBadge :value="run.status" /></td><td><div class="row-actions">
                <button type="button" class="button primary compact" :disabled="Boolean(exporting)" @click="exportReport(run.id, 'html', run.projectId)">在线查看</button>
                <ActionDropdown :label="exporting.startsWith(`${run.id}-`) ? '生成中…' : '导出'" :disabled="Boolean(exporting)" :items="[{ label: 'Word 文档', value: 'docx' }, { label: 'PDF 文档', value: 'pdf' }]" @select="exportReport(run.id, $event as 'docx' | 'pdf', run.projectId)" />
                <RouterLink :to="`/reports/${run.id}/acceptance`" class="row-action-link">整改验收</RouterLink>
              </div></td></tr></tbody>
        </table>
      </div>
      <EmptyState v-else title="暂无可导出的报告" description="完成至少一轮扫描后，CodeSentry 会在这里生成带基线信息和整改建议的报告。" action="发起扫描" to="/scans/new" />
    </section>
  </div>
</template>

<style scoped>
.reports-table { min-width: 1200px; }
.actions-heading { text-align: right; }
.report-count-detail { margin-top: 0.35rem; color: var(--muted); font-size: 0.8rem; line-height: 1.5; }
</style>
