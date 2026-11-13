<script setup lang="ts">
import { onMounted, ref } from 'vue'
import MetricCard from '@/components/ui/MetricCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { api } from '@/api/codesentry'
import { findingTitle } from '@/utils/labels'
import { formatChinaTime } from '@/utils/datetime'
import type { DashboardSummary, FindingView, ScanProject, ScanRun } from '@/types'

const summary = ref<DashboardSummary>({ businessProjects: 0, assets: 0, codeAssets: 0, jarAssets: 0, unassignedAssets: 0, projects: 0, scans: 0, runningScans: 0, newFindings: 0 })
const runs = ref<ScanRun[]>([])
const findings = ref<FindingView[]>([])
const projects = ref<ScanProject[]>([])
const error = ref('')

const projectName = (id: number) => projects.value.find((item) => item.id === id)?.displayName ?? projects.value.find((item) => item.id === id)?.projectName ?? `资产-${id}`
const shortCommit = (value?: string) => value?.slice(0, 8) ?? '待解析'
const formatTime = formatChinaTime

onMounted(async () => {
  try {
    const [summaryData, runData, findingData, projectData] = await Promise.all([
      api.dashboard(), api.scanRuns(8), api.findings(6), api.projects(),
    ])
    summary.value = summaryData
    runs.value = runData
    findings.value = findingData
    projects.value = projectData
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '无法连接 CodeSentry 后端'
  }
})
</script>

<template>
  <div class="page-stack">
    <div v-if="error" class="error-banner">{{ error }}。页面已进入只读空状态，请确认后端和 MySQL 已启动。</div>

    <section class="metric-grid dashboard-metrics">
      <MetricCard index="01" label="业务项目" :value="summary.businessProjects" note="已创建的业务项目" tone="calm" />
      <MetricCard index="02" label="扫描资产" :value="summary.assets" :note="`代码 ${summary.codeAssets} / JAR ${summary.jarAssets} · 未归属 ${summary.unassignedAssets}`" tone="calm" />
      <MetricCard index="03" label="扫描批次" :value="summary.scans" note="全部历史扫描批次" tone="neutral" />
      <MetricCard index="04" label="执行中的扫描" :value="summary.runningScans" note="含排队、准备与执行中" tone="signal" />
      <MetricCard index="05" label="累计新增问题" :value="summary.newFindings" note="各扫描批次新增数累计" tone="danger" />
    </section>

    <section class="split-grid">
      <article class="panel">
        <header class="panel-header"><h3>最近扫描记录</h3><small>扫描执行记录</small></header>
        <div v-if="runs.length" class="table-scroll">
          <table class="data-table">
            <thead><tr><th>任务 / 资产</th><th>模式</th><th>提交</th><th>发现</th><th>状态</th><th class="scan-timestamp-heading">开始时间</th></tr></thead>
            <tbody>
              <tr v-for="run in runs" :key="run.id">
                <td><strong>#{{ run.id }}</strong><div class="muted" style="margin-top:4px">{{ projectName(run.projectId) }}</div></td>
                <td><span class="mono">{{ run.scanMode === 'FULL' ? '全量' : '增量' }}</span></td>
                <td><span class="mono">{{ shortCommit(run.scanCommit) }}</span></td>
                <td><b :class="run.newFindings ? 'danger-text' : 'signal-text'">+{{ run.newFindings }}</b> / {{ run.totalFindings }}</td>
                <td><StatusBadge :value="run.status" /></td>
                <td class="mono scan-timestamp"><time class="datetime-text">{{ formatTime(run.createdAt) }}</time></td>
              </tr>
            </tbody>
          </table>
        </div>
        <EmptyState v-else title="尚无扫描活动" description="登记代码或上传 JAR 后，发起第一轮全量基线扫描。" action="管理扫描资产" to="/projects" />
      </article>

      <article class="panel">
        <header class="panel-header"><h3>最近发现问题</h3><small>按发现时间展示</small></header>
        <div v-if="findings.length" class="risk-list">
          <div v-for="(finding, index) in findings" :key="finding.instanceId ?? finding.id" class="risk-item">
            <span class="risk-rank">{{ String(index + 1).padStart(2, '0') }}</span>
            <div><h4 :title="finding.title">{{ findingTitle(finding) }}</h4><p :title="`${finding.filePath}:${finding.startLine ?? '-'}`">{{ finding.filePath }}:{{ finding.startLine ?? '-' }}</p></div>
            <StatusBadge :value="finding.severity" />
          </div>
        </div>
        <EmptyState v-else title="暂无问题记录" description="完成扫描后，最近发现的问题会在这里展示，审核详情可在问题台账查看。" />
      </article>
    </section>

    <section class="section-heading">
      <div><h2>执行原则</h2><p>CodeSentry 始终把自动扫描结论交给人工复核，不会直接修改业务代码。</p></div>
      <span class="section-code">人工参与审核</span>
    </section>
  </div>
</template>

<style scoped>
/* 五项统计在桌面保持同一行；窄屏保留足够宽度，避免资产明细挤压。 */
.dashboard-metrics { grid-template-columns: repeat(5, minmax(0, 1fr)); }
@media (max-width: 1200px) { .dashboard-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 900px) { .dashboard-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) { .dashboard-metrics { grid-template-columns: 1fr; } }
</style>
