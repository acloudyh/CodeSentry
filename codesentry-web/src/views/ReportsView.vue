<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/codesentry'
import EmptyState from '@/components/ui/EmptyState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { ScanProject, ScanRun } from '@/types'

const runs = ref<ScanRun[]>([])
const projects = ref<ScanProject[]>([])
const error = ref('')
const projectName = (id: number) => projects.value.find((item) => item.id === id)?.projectName ?? `PROJECT-${id}`
const exporting = ref('')
function exportReport(runId: number, format: 'html' | 'docx' | 'pdf') {
  const key = `${runId}-${format}`
  exporting.value = key
  const link = document.createElement('a')
  link.href = api.reportUrl(runId, format)
  link.download = `codesentry-report-${runId}.${format}`
  document.body.appendChild(link); link.click(); link.remove()
  window.setTimeout(() => { exporting.value = '' }, 1200)
}

onMounted(async () => {
  try {
    const [allRuns, allProjects] = await Promise.all([api.scanRuns(100), api.projects()])
    runs.value = allRuns.filter((item) => item.status === 'COMPLETED' || item.status === 'PARTIAL')
    projects.value = allProjects
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '报告列表加载失败' }
})
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>整改报告</h2><p>只导出非误报问题，并自动附带已有的 AI 初审、AI 复审、可信度和综合建议。</p></div><span class="section-code">REPORT VAULT</span></section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <section class="panel">
      <div v-if="runs.length" class="table-scroll">
        <table class="data-table">
          <thead><tr><th>报告编号</th><th>项目</th><th>扫描类型</th><th>统计</th><th>状态</th><th>导出</th></tr></thead>
          <tbody><tr v-for="run in runs" :key="run.id"><td class="mono">CSR-{{ String(run.id).padStart(6,'0') }}</td><td>{{ projectName(run.projectId) }}</td><td>{{ run.scanMode === 'FULL' ? '全量整改报告' : '增量整改报告' }}</td><td><b class="danger-text">{{ run.newFindings }}</b> 新增 / {{ run.totalFindings }} 当前</td><td><StatusBadge :value="run.status" /></td><td><div class="report-actions"><button v-for="format in (['html','docx','pdf'] as const)" :key="format" class="button secondary compact" :disabled="Boolean(exporting)" @click="exportReport(run.id, format)">{{ exporting === `${run.id}-${format}` ? '生成中…' : format.toUpperCase() }}</button></div></td></tr></tbody>
        </table>
      </div>
      <EmptyState v-else title="暂无可导出的报告" description="完成至少一轮扫描后，CodeSentry 会在这里生成带基线信息和整改建议的报告。" action="发起扫描" to="/scans/new" />
    </section>
  </div>
</template>
