<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/codesentry'
import { ruleProfileLabel, scannerLabel } from '@/utils/labels'
import { formatChinaTime } from '@/utils/datetime'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import type { FindingView, ScanProject, ScanRunDetail, ScanToolRun } from '@/types'

const route = useRoute()
const detail = ref<ScanRunDetail>()
const projects = ref<ScanProject[]>([])
const findings = ref<FindingView[]>([])
const error = ref('')
const stopping = ref(false)
let loadSequence = 0
async function stopScan() {
  if (!window.confirm('确认停止扫描？本批次结果将标记为不完整。')) return
  stopping.value = true
  ++loadSequence
  try {
    const cancelled = await api.cancelScan(runId.value)
    if (detail.value) detail.value = { ...detail.value, run: cancelled }
  }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '停止扫描失败' }
  finally { stopping.value = false; await load() }
}
let timer: number | undefined

const runId = computed(() => Number(route.params.id))
const run = computed(() => detail.value?.run)
const projectName = computed(() => {
  const projectId = run.value?.projectId
  return projects.value.find((item) => item.id === projectId)?.projectName ?? (projectId ? `项目-${projectId}` : '-')
})
const active = computed(() => ['PENDING', 'PREPARING', 'RUNNING'].includes(run.value?.status ?? ''))
const formatTime = formatChinaTime
const shortCommit = (value?: string) => value ? value.slice(0, 12) : '-'
const phaseLabel = (phase: string) => phase === 'baseline' ? '基线快照' : phase === 'availability' ? '可用性检查' : '扫描快照'
const logText = (item: ScanToolRun) => item.outputLog || item.errorMessage || '暂无命令输出。'
const scanModeLabel = computed(() => run.value?.scanMode === 'FULL' ? '全量扫描' : '增量扫描')
const workspaceLabel = computed(() => run.value?.workspaceMode === 'JAR_UPLOAD' ? 'JAR 上传' : run.value?.workspaceMode === 'CURRENT' ? '当前工作区' : '分支快照')

async function load() {
  if (stopping.value) return
  const sequence = ++loadSequence
  try {
    const [detailData, projectData, findingData] = await Promise.all([
      api.scanRunDetail(runId.value),
      api.projects(),
      api.runFindings(runId.value),
    ])
    if (sequence !== loadSequence) return
    detail.value = detailData
    projects.value = projectData
    findings.value = findingData
    error.value = ''
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '扫描详情加载失败'
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(() => {
    if (active.value) load()
  }, 3000)
})
onBeforeUnmount(() => timer && window.clearInterval(timer))
</script>

<template>
  <div class="page-stack">
    <section class="section-heading">
      <div>
        <h2>扫描批次 #{{ String(runId).padStart(5, '0') }}</h2>
        <p>查看任务进度、扫描器命令、退出码和执行日志。</p>
      </div>
      <div class="form-actions"><button v-if="active" class="button danger" :disabled="stopping" @click="stopScan">{{ stopping ? '停止中…' : '停止扫描' }}</button><RouterLink to="/scans" class="button secondary">返回扫描记录</RouterLink></div>
    </section>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <template v-if="detail && run">
      <section class="detail-grid">
        <div class="panel">
          <div class="panel-header">
            <h3>执行进度</h3>
            <StatusBadge :value="run.status" />
          </div>
          <div class="progress-panel">
            <div class="progress-meter">
              <span :style="{ width: `${detail.progressPercent}%` }"></span>
            </div>
            <div class="progress-number">{{ detail.progressPercent }}%</div>
            <div v-if="run.errorMessage" class="notice danger-note">{{ run.errorMessage }}</div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-header"><h3>任务信息</h3><small>{{ projectName }}</small></div>
          <div class="kv-grid">
            <span>扫描模式</span><b>{{ scanModeLabel }}</b>
            <span>工作区</span><b>{{ workspaceLabel }}</b>
            <span>{{ run.workspaceMode === 'JAR_UPLOAD' ? '上传文件' : '扫描分支' }}</span><b>{{ run.scanRef ?? '当前工作区' }}</b>
            <span>基线分支</span><b>{{ run.baselineRef ?? '无基线' }}</b>
            <span>扫描规则集</span><b :title="run.ruleProfile">{{ ruleProfileLabel(run.ruleProfile) }}</b>
            <span>{{ run.workspaceMode === 'JAR_UPLOAD' ? '文件 SHA-256' : '扫描提交' }}</span><b class="mono">{{ shortCommit(run.scanCommit) }}</b>
            <span>共同祖先提交</span><b class="mono">{{ shortCommit(run.mergeBaseCommit) }}</b>
            <span>开始时间</span><b>{{ formatTime(run.startedAt) }}</b>
            <span>结束时间</span><b>{{ formatTime(run.finishedAt) }}</b>
          </div>
        </div>
      </section>

      <section class="metric-grid">
        <div class="mini-stat"><span>总问题</span><b>{{ run.totalFindings }}</b></div>
        <div class="mini-stat"><span>新增</span><b class="danger-text">{{ run.newFindings }}</b></div>
        <div class="mini-stat"><span>修复</span><b class="signal-text">{{ run.fixedFindings }}</b></div>
        <div class="mini-stat"><span>明细实例</span><b>{{ findings.length }}</b></div>
      </section>

      <section class="panel">
        <div class="panel-header"><h3>扫描器执行日志</h3><small>{{ detail.toolRuns.length }} 条记录</small></div>
        <div class="tool-run-list">
          <article v-for="item in detail.toolRuns" :key="item.id" class="tool-run-card">
            <div class="tool-run-head">
              <div>
                <strong>{{ scannerLabel(item.toolName) }}</strong>
                <small>{{ phaseLabel(item.phase) }} · {{ formatTime(item.startedAt) }} → {{ formatTime(item.finishedAt) }}</small>
              </div>
              <StatusBadge :value="item.status" />
            </div>
            <div class="tool-meta">
              <span>退出码 <b>{{ item.exitCode ?? '-' }}</b></span>
              <span>报告 <b>{{ item.rawReportPath ?? '-' }}</b></span>
              <span>目录 <b>{{ item.workingDirectory ?? '-' }}</b></span>
            </div>
            <pre v-if="item.commandLine" class="command-line">{{ item.commandLine }}</pre>
            <pre class="log-box">{{ logText(item) }}</pre>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>
