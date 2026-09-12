<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '@/api/codesentry'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import type { ScanProject, ScanRun } from '@/types'

const runs = ref<ScanRun[]>([])
const projects = ref<ScanProject[]>([])
const error = ref('')
let timer: number | undefined

const projectName = (id: number) => projects.value.find((item) => item.id === id)?.projectName ?? `PROJECT-${id}`
const formatTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
const modeLabel = (run: ScanRun) => run.scanMode === 'FULL' ? '全量扫描' : '增量审查'
const workspaceLabel = (run: ScanRun) => run.workspaceMode === 'CURRENT' ? '当前工作区' : '分支快照'

async function load() {
  try { [runs.value, projects.value] = await Promise.all([api.scanRuns(100), api.projects()]) }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '扫描记录加载失败' }
}

onMounted(() => { load(); timer = window.setInterval(load, 5000) })
onBeforeUnmount(() => timer && window.clearInterval(timer))
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>执行历史</h2><p>页面每 5 秒刷新一次；扫描器缺失时任务会标记为部分完成，而不是伪装成零风险。</p></div><RouterLink to="/scans/new" class="button primary">+ 发起扫描</RouterLink></section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <section class="panel">
      <div v-if="runs.length" class="table-scroll">
        <table class="data-table">
          <thead><tr><th>任务</th><th>项目</th><th>模式 / 工作区</th><th>分支 → 基线</th><th>结果统计</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="run in runs" :key="run.id">
              <td><strong>#{{ String(run.id).padStart(5,'0') }}</strong><div v-if="run.repositoryDirty" class="danger-text" style="font-size:10px;margin-top:5px">含未提交内容</div></td>
              <td>{{ projectName(run.projectId) }}</td>
              <td><span>{{ modeLabel(run) }}</span><div class="muted" style="margin-top:4px">{{ workspaceLabel(run) }}</div></td>
              <td><div>{{ run.scanRef ?? '当前工作区' }}</div><div class="muted" style="margin-top:4px">→ {{ run.baselineRef ?? '无基线' }}</div></td>
              <td><b class="danger-text">+{{ run.newFindings }}</b><span class="muted"> 新增</span> · <b class="signal-text">-{{ run.fixedFindings }}</b><span class="muted"> 修复</span><div class="mono" style="margin-top:4px">合计 {{ run.totalFindings }}</div></td>
              <td><StatusBadge :value="run.status" /><div v-if="run.errorMessage" class="danger-text clamp-error" style="font-size:10px;margin-top:6px" :title="run.errorMessage">{{ run.errorMessage }}</div></td>
              <td class="mono">{{ formatTime(run.createdAt) }}</td>
              <td><RouterLink :to="`/scans/${run.id}`" class="button secondary compact">查看详情</RouterLink></td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else title="尚无扫描记录" description="选择一个代码仓库，执行全量基线扫描或基于分支的增量审查。" action="发起第一轮扫描" to="/scans/new" />
    </section>
  </div>
</template>
