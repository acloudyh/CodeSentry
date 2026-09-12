<script setup lang="ts">
import { onMounted, ref } from 'vue'
import MetricCard from '@/components/ui/MetricCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { api } from '@/api/codesentry'
import type { DashboardSummary, FindingView, ScanProject, ScanRun } from '@/types'

const summary = ref<DashboardSummary>({ projects: 0, scans: 0, runningScans: 0, newFindings: 0 })
const runs = ref<ScanRun[]>([])
const findings = ref<FindingView[]>([])
const projects = ref<ScanProject[]>([])
const error = ref('')

const projectName = (id: number) => projects.value.find((item) => item.id === id)?.projectName ?? `PROJECT-${id}`
const shortCommit = (value?: string) => value?.slice(0, 8) ?? '待解析'
const formatTime = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })

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

    <section class="metric-grid">
      <MetricCard index="01" label="受管仓库" :value="summary.projects" note="本地与远程镜像" tone="calm" />
      <MetricCard index="02" label="扫描批次" :value="summary.scans" note="最近 200 次统计" tone="neutral" />
      <MetricCard index="03" label="执行中的任务" :value="summary.runningScans" note="本地节点实时状态" tone="signal" />
      <MetricCard index="04" label="累计新增问题" :value="summary.newFindings" note="等待复核与整改" tone="danger" />
    </section>

    <section class="split-grid">
      <article class="panel">
        <header class="panel-header"><h3>最近扫描活动</h3><small>LIVE EXECUTION FEED</small></header>
        <div v-if="runs.length" class="table-scroll">
          <table class="data-table">
            <thead><tr><th>任务 / 仓库</th><th>模式</th><th>提交</th><th>发现</th><th>状态</th><th>开始时间</th></tr></thead>
            <tbody>
              <tr v-for="run in runs" :key="run.id">
                <td><strong>#{{ run.id }}</strong><div class="muted" style="margin-top:4px">{{ projectName(run.projectId) }}</div></td>
                <td><span class="mono">{{ run.scanMode === 'FULL' ? '全量' : '增量' }}</span></td>
                <td><span class="mono">{{ shortCommit(run.scanCommit) }}</span></td>
                <td><b :class="run.newFindings ? 'danger-text' : 'signal-text'">+{{ run.newFindings }}</b> / {{ run.totalFindings }}</td>
                <td><StatusBadge :value="run.status" /></td>
                <td class="mono">{{ formatTime(run.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <EmptyState v-else title="尚无扫描活动" description="登记本地或远程仓库后，发起第一轮全量基线扫描。" action="登记代码仓库" to="/projects" />
      </article>

      <article class="panel">
        <header class="panel-header"><h3>高优先级信号</h3><small>MANUAL TRIAGE QUEUE</small></header>
        <div v-if="findings.length" class="risk-list">
          <div v-for="(finding, index) in findings" :key="finding.instanceId ?? finding.id" class="risk-item">
            <span class="risk-rank">{{ String(index + 1).padStart(2, '0') }}</span>
            <div><h4>{{ finding.title }}</h4><p :title="`${finding.filePath}:${finding.startLine ?? '-'}`">{{ finding.filePath }}:{{ finding.startLine ?? '-' }}</p></div>
            <StatusBadge :value="finding.severity" />
          </div>
        </div>
        <EmptyState v-else title="暂无问题信号" description="完成扫描后，新增和存量问题会在这里按风险等级排列。" />
      </article>
    </section>

    <section class="section-heading">
      <div><h2>执行原则</h2><p>CodeSentry 始终把自动扫描结论交给人工复核，不会直接修改业务代码。</p></div>
      <span class="section-code">HUMAN-IN-THE-LOOP / 001</span>
    </section>
  </div>
</template>
