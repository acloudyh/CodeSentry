<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/codesentry'
import type { ReportAcceptance } from '@/types'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { formatChinaTime } from '@/utils/datetime'

const route = useRoute()
const data = ref<ReportAcceptance | null>(null)
const error = ref('')
const loading = ref(false)
const selectedBatch = ref<number | undefined>()
const filter = ref('ALL')
const editing = ref<ReportAcceptance['items'][number] | null>(null)
const note = ref('')
const reviewer = ref(localStorage.getItem('codesentry-reviewer') ?? '本地复核人')
const saving = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
const labels: Record<string, string> = {
  TODO: '待整改', PENDING_VERIFICATION: '已整改待验证', NOT_DETECTED: '复扫未再检出',
  STILL_PRESENT: '仍然存在', NEEDS_REVIEW: '同类风险待复核', UNVERIFIED: '尚未完成验证', BASELINE_FIXED: '原批次已修复',
}
const rows = computed(() => data.value?.items.filter(row => filter.value === 'ALL' || row.status === filter.value) ?? [])
const reportNumber = computed(() => `CSR-${String(route.params.id).padStart(6, '0')}`)
async function load() {
  const requestGeneration = ++generation
  if (timer) clearTimeout(timer)
  loading.value = true; error.value = ''
  try {
    const response = await api.reportAcceptance(Number(route.params.id), selectedBatch.value)
    if (requestGeneration !== generation) return
    data.value = response
    if (response.verification && ['PENDING', 'PREPARING', 'RUNNING'].includes(response.verification.status)) {
      timer = setTimeout(load, 5000)
    }
  } catch (reason) { if (requestGeneration === generation) error.value = reason instanceof Error ? reason.message : '验收结果加载失败' }
  finally { if (requestGeneration === generation) loading.value = false }
}
function edit(row: ReportAcceptance['items'][number]) {
  editing.value = row; note.value = row.comment ?? ''
}
async function save() {
  if (!editing.value) return
  saving.value = true; error.value = ''
  try {
    await api.triageFinding(editing.value.finding.id, { triageStatus: 'FIXED_PENDING', comment: note.value, reviewer: reviewer.value })
    localStorage.setItem('codesentry-reviewer', reviewer.value)
    editing.value = null
    await load()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '整改记录保存失败' }
  finally { saving.value = false }
}
watch(() => route.params.id, () => { selectedBatch.value = undefined; data.value = null; filter.value = 'ALL'; editing.value = null; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++; if (timer) clearTimeout(timer) })
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>整改验收 <span class="mono">{{ reportNumber }}</span></h2><p>原报告问题逐条跟踪，保留整改说明和每次复扫批次。</p></div><RouterLink to="/reports" class="button secondary">返回报告</RouterLink></section>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <section v-if="data" class="panel panel-pad">
      <div class="acceptance-toolbar">
        <div><span class="muted">原报告清单</span><h3>{{ data.items.length }} 条问题</h3></div>
        <div class="report-actions"><button class="button secondary" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新结果' }}</button><RouterLink :to="{ path: '/projects', query: { scan: '1', sourceReportRunId: data.source.id } }" class="button primary">发起验收复扫</RouterLink></div>
      </div>
      <p class="muted">首次导出或进入验收时归档问题清单。此前导出的旧文件无法自动还原，请核对清单后开始验收。依赖漏洞按每个 CVE 验证。</p>
      <div class="field"><label for="verification-batch">验证批次</label><select id="verification-batch" v-model="selectedBatch" class="select" @change="load"><option :value="undefined">最新验收复扫</option><option v-for="run in data.history" :key="run.id" :value="run.id">#{{ run.id }} · {{ formatChinaTime(run.createdAt) }}</option></select></div>
      <p v-if="data.verification">本次验证：<RouterLink :to="`/scans/${data.verification.id}`">#{{ data.verification.id }}</RouterLink> · <StatusBadge :value="data.verification.status" /> · {{ data.verification.scanCommit?.slice(0, 12) ?? '尚未读取代码版本' }}{{ data.verification.repositoryDirty ? '（含工作区未提交修改）' : '' }}</p>
      <p v-else class="muted">尚未关联验收复扫。修改代码、记录整改说明后，点击“发起验收复扫”。</p>
      <div class="notice">“复扫未再检出”表示原扫描器本次完整扫描没有命中；不等于人工验收通过。扫描失败、停止、结果不完整或缺少原扫描器时，保持“尚未完成验证”。同文件同类规则仍命中时，需要人工复核。</div>
    </section>
    <section v-if="data" class="panel">
      <div class="acceptance-filters"><button class="button secondary compact" :class="{active: filter === 'ALL'}" @click="filter = 'ALL'">全部 {{ data.items.length }}</button><button v-for="(count, state) in data.counts" :key="state" class="button secondary compact" :class="{active: filter === state}" @click="filter = String(state)">{{ labels[state] ?? state }} {{ count }}</button></div>
      <div class="table-scroll"><table class="data-table acceptance-table"><thead><tr><th>原报告问题</th><th>位置</th><th>验收状态</th><th>整改说明 / 提交记录</th><th class="actions-heading">操作</th></tr></thead><tbody>
        <tr v-for="row in rows" :key="row.finding.instanceId ?? row.finding.id"><td><StatusBadge :value="row.finding.severity" /><div>{{ row.finding.title }}</div><small class="mono">F-{{ row.finding.id }}</small><small class="muted">{{ row.finding.scanner }} / {{ row.finding.ruleId }}</small></td><td class="mono">{{ row.finding.filePath }}<span v-if="row.finding.startLine">:{{ row.finding.startLine }}</span></td><td><strong :class="row.status === 'NOT_DETECTED' ? 'acceptance-clear' : row.status === 'STILL_PRESENT' ? 'danger-text' : ''">{{ labels[row.status] ?? row.status }}</strong></td><td class="acceptance-note">{{ row.comment || '尚未填写' }}<small v-if="row.reviewer" class="muted"> · {{ row.reviewer }}</small></td><td class="actions-cell"><button class="button secondary compact" @click="edit(row)">记录整改</button></td></tr>
        <tr v-if="!rows.length"><td colspan="5" class="muted">当前没有符合条件的问题。</td></tr>
      </tbody></table></div>
    </section>
    <form v-if="editing" class="panel panel-pad page-stack" @submit.prevent="save">
      <h3>记录整改 · {{ editing.finding.title }}</h3>
      <div class="field"><label for="remediation-note">整改说明 / 提交编号</label><textarea id="remediation-note" v-model="note" class="textarea" rows="4" required placeholder="说明修改内容、提交编号及验证要点"></textarea></div>
      <div class="field"><label for="remediation-reviewer">记录人</label><input id="remediation-reviewer" v-model="reviewer" class="input" required maxlength="128" /></div>
      <p class="muted">保存后标记为“已整改待验证”；已有复扫结果仍以选定验证批次为准，后续修改需要再次复扫。</p>
      <div class="form-actions"><button type="button" class="button secondary" :disabled="saving" @click="editing = null">取消</button><button class="button primary" :disabled="saving">{{ saving ? '保存中…' : '保存整改记录' }}</button></div>
    </form>
  </div>
</template>

<style scoped>
.acceptance-toolbar { display:flex; justify-content:space-between; gap:20px; align-items:center; flex-wrap:wrap }
.acceptance-filters { display:flex; flex-wrap:wrap; gap:8px; padding:20px }
.acceptance-filters .active { border-color:var(--accent); font-weight:700 }
.acceptance-note { white-space:pre-wrap; min-width:180px; max-width:340px }
.acceptance-clear { color:var(--cyan) }
h3 { margin:8px 0 16px }
.field { margin:18px 0 }
</style>

<style scoped>
.acceptance-table { min-width: 1080px; }
.acceptance-table th:last-child { width: 145px; }
.acceptance-table th:nth-child(3) { width: 145px; }
</style>
