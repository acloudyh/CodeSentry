<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/codesentry'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { RemediationReportSummary, ScanProject } from '@/types'

const runs = ref<RemediationReportSummary[]>([])
const projects = ref<ScanProject[]>([])
const error = ref('')
const projectName = (id: number) => projects.value.find((item) => item.id === id)?.projectName ?? `项目-${id}`
const exporting = ref('')
async function exportReport(runId: number, format: 'html' | 'docx' | 'pdf') {
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
    link.href = objectUrl; link.download = `codesentry-report-${runId}.${format}`
    document.body.appendChild(link); link.click(); link.remove()
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '报告下载失败'
  } finally {
    exporting.value = ''
  }
}

onMounted(async () => {
  try {
    const [allRuns, allProjects] = await Promise.all([api.reports(100), api.projects()])
    runs.value = allRuns
    projects.value = allProjects
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '报告列表加载失败' }
})
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>整改报告</h2><p>保留人工确认的风险，以及工具复核完成、通过可信度门槛且未判为误报的问题。</p></div><span class="section-code">报告列表</span></section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <section class="panel">
      <div v-if="runs.length" class="table-scroll">
        <table class="data-table">
          <thead><tr><th>报告编号</th><th>项目</th><th>扫描类型</th><th>统计</th><th>状态</th><th>查看 / 导出</th></tr></thead>
          <tbody><tr v-for="run in runs" :key="run.id"><td class="mono">CSR-{{ String(run.id).padStart(6,'0') }}</td><td>{{ projectName(run.projectId) }}</td><td>{{ run.scanMode === 'FULL' ? '全量整改报告' : '增量整改报告' }}</td><td><b class="danger-text">{{ run.newFindings }}</b> 新增 / {{ run.totalFindings }} 当前</td><td><StatusBadge :value="run.status" /></td><td><div class="report-actions"><button v-for="format in (['html','docx','pdf'] as const)" :key="format" class="button secondary compact" :disabled="Boolean(exporting)" @click="exportReport(run.id, format)">{{ exporting === `${run.id}-${format}` ? '生成中…' : ({ html: '在线查看', docx: 'Word 文档', pdf: 'PDF 文档' })[format] }}</button></div></td></tr></tbody>
        </table>
      </div>
      <EmptyState v-else title="暂无可导出的报告" description="完成至少一轮扫描后，CodeSentry 会在这里生成带基线信息和整改建议的报告。" action="发起扫描" to="/scans/new" />
    </section>
  </div>
</template>
