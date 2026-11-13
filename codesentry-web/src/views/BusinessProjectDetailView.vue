<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectRoundLabel, projectRoundScope, repositorySyncLabel, repositorySyncBusy, repositoryReady } from '@/utils/labels'
import { api } from '@/api/codesentry'
import type { BusinessProject, ScanProject, ProjectScanRound, RuleProfile, WorkspaceMode, BranchView } from '@/types'
import AppModal from '@/components/ui/AppModal.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ProjectLedger from '@/components/ProjectLedger.vue'
import { applicationTypeLabel, runtimeVersionLabel } from '@/utils/labels'
import { formatChinaTime } from '@/utils/datetime'
const route = useRoute(), router = useRouter(), id = Number(route.params.id)
const project = ref<BusinessProject>(), assets = ref<ScanProject[]>([]), rounds = ref<ProjectScanRound[]>([])
const selectedIds = ref<number[]>([]), current = ref<ProjectScanRound>(), tab = ref(String(route.query.tab ?? 'assets'))
const roundPage = ref(1), roundTotal = ref(0), error = ref(''), message = ref(''), busy = ref(false)
const linkOpen = ref(false), candidates = ref<ScanProject[]>([]), linkId = ref<number | null>(null)
const editOpen = ref(false), editName = ref(''), editDescription = ref('')
const scanOpen = ref(false), networkMode = ref<'LOCAL' | 'ONLINE'>('LOCAL'), ruleProfile = ref('COMMUNITY_GENERAL'), profiles = ref<RuleProfile[]>([])
const configAssets = ref<ScanProject[]>([]), fullScope = ref(false)
const targets = ref<Record<number, { workspaceMode: WorkspaceMode; scanRef?: string }>>({}), branches = ref<Record<number, BranchView[]>>({})
const refreshRemote = ref(false), syncingId = ref<number | null>(null)
const requestKey = ref(''), requestSignature = ref('')
const allSelected = computed(() => assets.value.length > 0 && assets.value.every(a => selectedIds.value.includes(a.id)))
const stats = ref<{ pendingReviewCount?: number; includedCount?: number }>({})
const totalAssets = computed(() => assets.value.length)
let timer: ReturnType<typeof setInterval> | undefined
async function load(keepRound = true) {
  const [p, a, r] = await Promise.all([api.businessProject(id), api.businessAssets(id), api.projectRounds(id, roundPage.value)])
  project.value = p; assets.value = a; rounds.value = r.items; roundTotal.value = r.total
  selectedIds.value = selectedIds.value.filter(v => a.some(item => item.id === v))
  const currentId = keepRound ? current.value?.id : undefined
  current.value = currentId ? r.items.find(v => v.id === currentId) ?? await api.projectRound(id, currentId) : r.items[0]
  if(current.value) stats.value = await api.projectFindings(id, new URLSearchParams({ issueType: 'SECURITY', roundId: String(current.value.id), pageSize: '1' }))
  else stats.value = {}
}
async function attempt(action: () => Promise<void>) { if(busy.value) return; busy.value = true; error.value = ''; try { await action() } catch(e) { error.value = (e as Error).message } finally { busy.value = false } }
async function configure(items: ScanProject[], all: boolean) {
  if(!items.length) return
  const unready = items.find(a => !repositoryReady(a))
  if(unready) { error.value = `“${unready.projectName}”代码尚未就绪，请等待拉取完成或重试。`; return }
  configAssets.value = items; fullScope.value = all; refreshRemote.value = false; targets.value = {}; branches.value = {}; error.value = ''; requestKey.value = crypto.randomUUID(); requestSignature.value = ''
  for(const a of items) targets.value[a.id] = { workspaceMode: a.sourceType === 'JAR_UPLOAD' ? 'JAR_UPLOAD' : a.sourceType === 'LOCAL' ? 'CURRENT' : 'BRANCH_SNAPSHOT', scanRef: a.sourceType === 'GIT' ? a.defaultBranch ?? 'main' : undefined }
  scanOpen.value = true
  try { profiles.value = await api.ruleProfiles(); await Promise.all(items.filter(a => a.sourceType !== 'JAR_UPLOAD').map(async a => { branches.value[a.id] = await api.branches(a.id) })) } catch(e) { error.value = (e as Error).message }
}
async function start() {
  await attempt(async () => {
    const base = { refreshRemote: networkMode.value === 'ONLINE' && refreshRemote.value, networkMode: networkMode.value, ruleProfile: ruleProfile.value, fullScope: fullScope.value, assets: configAssets.value.map(a => ({ assetId: a.id, ...targets.value[a.id], scanRef: targets.value[a.id]?.workspaceMode === 'BRANCH_SNAPSHOT' ? targets.value[a.id]?.scanRef || a.defaultBranch || 'main' : undefined })) }
    const signature = JSON.stringify(base); if(requestSignature.value && signature !== requestSignature.value) requestKey.value = crypto.randomUUID(); requestSignature.value = signature
    current.value = await api.startProjectRound(id, { ...base, requestKey: requestKey.value }); scanOpen.value = false; tab.value = 'rounds'; roundPage.value = 1; await load()
  })
}
async function syncAsset(asset: ScanProject) {
  syncingId.value = asset.id
  try { await attempt(async () => { message.value = `正在同步“${asset.projectName}”…`; await api.syncProject(asset.id); await load(); message.value = `“${asset.projectName}”已提交后台拉取，请查看资产状态。` }) }
  finally { syncingId.value = null }
}
async function link() { await attempt(async () => { candidates.value = (await api.projects()).filter(a => !a.businessProjectId); linkId.value = candidates.value[0]?.id ?? null; linkOpen.value = true }) }
async function attach() { if(linkId.value) await attempt(async () => { await api.attachAsset(id, linkId.value!); linkOpen.value = false; await load() }) }
async function detach(a: ScanProject) { if(window.confirm(`解除“${a.projectName}”的项目归属？历史扫描和报告会保留。`)) await attempt(async () => { await api.detachAsset(id, a.id); await load() }) }
function edit() { editName.value = project.value?.name ?? ''; editDescription.value = project.value?.description ?? ''; editOpen.value = true }
async function saveProject() { await attempt(async () => { project.value = await api.saveBusinessProject(id, { name: editName.value, description: editDescription.value }); editOpen.value = false }) }
async function cancel() { if(current.value && window.confirm('停止本轮所有尚未结束的扫描？已完成结果会保留。')) await attempt(async () => { current.value = await api.cancelProjectRound(id, current.value!.id); await load() }) }
const retryKey = ref(''), retryRound = ref<number>()
async function retry() { if(!current.value) return; await attempt(async () => { if(retryRound.value !== current.value!.id) { retryRound.value = current.value!.id; retryKey.value = crypto.randomUUID() } current.value = await api.retryProjectRound(id, current.value!.id, retryKey.value); roundPage.value = 1; await load() }) }
async function exportReport(format: 'html' | 'docx' | 'pdf') {
  if(!current.value) return
  if(format === 'html') { window.open(`/business-projects/${id}/reports/${current.value.id}/view`, '_blank', 'noopener,noreferrer'); return }
  await attempt(async () => {
    const response = await fetch(api.projectReportUrl(id, current.value!.id, format)); if(!response.ok) { const body = await response.json().catch(() => null); throw new Error(body?.message ?? '项目报告导出失败') }
    const url = URL.createObjectURL(await response.blob()), a = document.createElement('a'); a.href = url; a.download = `${project.value?.name.replace(/[\\/:*?"<>|]/g, '_')}-${current.value!.assetHistory ? `资产历史扫描${-current.value!.id}` : `项目整改报告-P${current.value!.id}`}.${format}`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); message.value = current.value!.assetHistory ? '已导出原始资产扫描报告，保留原扫描与审核记录。' : '项目报告已归档。本轮报告内容固定，可重复导出。'
  })
}
function toggle(a: ScanProject) { selectedIds.value = selectedIds.value.includes(a.id) ? selectedIds.value.filter(v => v !== a.id) : [...selectedIds.value, a.id] }
watch(() => current.value?.id, async roundId => { if(roundId) { try { stats.value = await api.projectFindings(id, new URLSearchParams({ issueType: 'SECURITY', roundId: String(roundId), pageSize: '1' })) } catch(e) { error.value = (e as Error).message } } })
watch(roundPage, () => attempt(async () => { current.value = undefined; await load(false) }))
onMounted(async () => { await attempt(async () => { await load(); if(route.query.scan === 'all') { await configure(assets.value, true); await router.replace({ path: route.path }) } }); timer = setInterval(async () => { if(!busy.value && (current.value?.status === 'RUNNING' || rounds.value.some(r => r.status === 'RUNNING') || assets.value.some(repositorySyncBusy))) { try { await load() } catch(e) { error.value = (e as Error).message } } }, 5000) })
onBeforeUnmount(() => { if(timer) clearInterval(timer) })
</script>
<template>
  <div class="page-stack">
    <section class="section-heading"><div><RouterLink to="/business-projects" class="eyebrow">项目概览 /</RouterLink><h2>{{ project?.name ?? '加载项目…' }}</h2><p>{{ project?.description || '统一管理业务项目的代码仓库与 JAR 安全整改。' }}</p></div><div class="report-actions"><button class="button secondary" :disabled="busy" @click="edit">编辑项目</button><button class="button primary" :disabled="busy || !assets.length" @click="configure(assets, true)">扫描整个项目</button></div></section>
    <div class="error-banner" v-if="error" role="alert">{{ error }}</div><div class="notice" v-if="message" role="status">{{ message }}</div>
    <div class="project-metrics"><section><span>扫描资产</span><strong>{{ totalAssets }}</strong><small>代码 {{ assets.filter(a => a.sourceType !== 'JAR_UPLOAD').length }} / JAR {{ assets.filter(a => a.sourceType === 'JAR_UPLOAD').length }}</small></section><section><span>{{ current ? projectRoundLabel(current) : '项目扫描轮次' }}</span><strong>{{ current ? `${current.items.length}/${current.assetCount}` : '—' }}</strong><small>{{ current ? projectRoundScope(current) : '尚未发起扫描' }} · 共 {{ roundTotal }} 条扫描记录</small></section><section><span>完整完成</span><strong>{{ current?.completedCount ?? '—' }}</strong><small>非完整完成 {{ current?.failedCount ?? 0 }} 个资产</small></section><section><span>待审核问题</span><strong>{{ stats.pendingReviewCount ?? '—' }}</strong><small>当前轮次，尚未审核</small></section><section><span>已采纳整改</span><strong>{{ stats.includedCount ?? '—' }}</strong><small>沿用单资产报告采纳规则</small></section></div>
    <nav class="segmented project-tabs" aria-label="项目视图"><button v-for="t in [['assets','扫描资产'],['rounds','扫描轮次'],['ledger','整改台账'],['reports','整改报告']]" :key="t[0]" :class="{ active: tab === t[0] }" @click="tab = t[0]!">{{ t[1] }}</button></nav>
    <template v-if="tab === 'assets'">
      <div class="section-heading"><div class="report-actions"><RouterLink class="button secondary" :to="`/projects?businessProjectId=${id}&register=1`">+ 登记仓库</RouterLink><RouterLink class="button secondary" :to="`/projects?businessProjectId=${id}&new=jar`">+ 上传 JAR</RouterLink><button class="button secondary" :disabled="busy" @click="link">关联已有资产</button></div><button class="button primary" :disabled="busy || !selectedIds.length" @click="configure(assets.filter(a => selectedIds.includes(a.id)), false)">扫描选中项（{{ selectedIds.length }}）</button></div>
      <section class="panel"><div v-if="assets.length" class="table-scroll"><table class="data-table project-assets-table"><thead><tr><th><input type="checkbox" aria-label="选择全部资产" :checked="allSelected" @change="selectedIds = allSelected ? [] : assets.map(a => a.id)" /></th><th>扫描资产</th><th>类型 / 运行环境</th><th>分支 / 文件</th><th>代码状态</th><th>本轮扫描</th><th class="actions-heading">操作</th></tr></thead><tbody><tr v-for="a in assets" :key="a.id"><td><input type="checkbox" :aria-label="`选择 ${a.projectName}`" :checked="selectedIds.includes(a.id)" @change="toggle(a)" /></td><td><strong>{{ a.projectName }}</strong><div class="muted mono">A-{{ a.id }}</div></td><td>{{ a.sourceType === 'JAR_UPLOAD' ? 'JAR' : a.sourceType === 'GIT' ? 'Git 仓库' : '本地代码' }}<div class="muted">{{ applicationTypeLabel(a.applicationType) }} · {{ runtimeVersionLabel(a) }}</div></td><td class="mono">{{ a.sourceType === 'JAR_UPLOAD' ? a.localPath?.split('/').pop() : a.defaultBranch }}</td><td><span v-if="a.sourceType === 'GIT'" :title="a.lastSyncMessage">{{ repositorySyncLabel(a) }}</span><span v-else>本地资产</span></td><td><StatusBadge v-if="current?.items.find(i => i.assetId === a.id)" :value="current.items.find(i => i.assetId === a.id)!.status" /><span v-else class="muted">本轮未扫描</span></td><td class="actions-cell"><div class="row-actions"><button v-if="a.sourceType === 'GIT'" class="row-action-link" :disabled="busy || repositorySyncBusy(a)" @click="syncAsset(a)">{{ repositorySyncBusy(a) ? repositorySyncLabel(a) : a.lastSyncStatus === 'FAILED' ? '重试拉取' : '同步代码' }}</button><button class="row-action-link" :disabled="busy || !repositoryReady(a)" @click="configure([a], false)">扫描</button><RouterLink class="row-action-link" :to="`/projects?businessProjectId=${id}&type=${a.sourceType === 'JAR_UPLOAD' ? 'jar' : 'repository'}`">编辑资产</RouterLink><button class="row-action-link danger" :disabled="busy" @click="detach(a)">解除归属</button></div></td></tr></tbody></table></div><EmptyState v-else title="项目中还没有扫描资产" description="登记代码仓库、上传 JAR，或关联已有资产。" /></section>
    </template>
    <ProjectLedger v-else-if="tab === 'ledger'" :business-project-id="id" :round-id="current?.id" @changed="load()" />
    <template v-else>
      <section class="panel round-selector"><div class="field"><label>扫描记录 / 轮次</label><select class="select" :value="current?.id" @change="current = rounds.find(r => r.id === Number(($event.target as HTMLSelectElement).value))"><option v-for="r in rounds" :key="r.id" :value="r.id">{{ projectRoundLabel(r) }} · {{ projectRoundScope(r) }} · {{ formatChinaTime(r.createdAt) }}</option></select></div><div class="row-actions"><span>共 {{ roundTotal }} 条扫描记录</span><button class="button secondary compact" :disabled="roundPage <= 1 || busy" @click="roundPage--">上一页</button><button class="button secondary compact" :disabled="roundPage*20 >= roundTotal || busy" @click="roundPage++">下一页</button></div></section>
      <template v-if="current"><section class="section-heading"><div><h3>{{ projectRoundLabel(current) }} <StatusBadge :value="current.status" /></h3><p>{{ projectRoundScope(current) }} · 覆盖 {{ current.items.length }}/{{ current.assetCount }} · {{ current.networkMode === 'LOCAL' ? '本地扫描' : '联网扫描' }}<span v-if="current.retryOf"> · 重试自 #P{{ current.retryOf }}</span></p></div><div class="report-actions"><template v-if="tab === 'reports'"><button class="button secondary" :disabled="busy || current.status === 'RUNNING' || (current.assetHistory && !['COMPLETED','PARTIAL'].includes(current.status))" @click="exportReport('html')">在线查看</button><button class="button secondary" :disabled="busy || current.status === 'RUNNING' || (current.assetHistory && !['COMPLETED','PARTIAL'].includes(current.status))" @click="exportReport('docx')">导出 Word</button><button class="button primary" :disabled="busy || current.status === 'RUNNING' || (current.assetHistory && !['COMPLETED','PARTIAL'].includes(current.status))" @click="exportReport('pdf')">{{ busy ? '处理中…' : '导出 PDF' }}</button></template><template v-else><button v-if="!current.assetHistory && current.status === 'RUNNING'" class="button secondary" :disabled="busy" @click="cancel">停止本轮</button><button v-if="!current.assetHistory && current.failedCount" class="button primary" :disabled="busy" @click="retry">重试失败项</button></template></div></section>
      <div class="notice" v-if="tab === 'reports' && current.assetHistory">此处查看或导出原始资产扫描报告，沿用来源扫描编号与原有审核、采纳结果；不会创建新的项目扫描轮次。</div><div class="notice" v-else-if="tab === 'reports'">项目报告按本轮归档，包含各资产整改明细与未完成扫描清单。首次查看或导出会固定内容，失败和未扫描资产不代表检查通过。</div>
      <section class="panel"><div class="table-scroll"><table class="data-table project-rounds-table"><thead><tr><th>资产</th><th>来源扫描</th><th>扫描目标 / 实际版本</th><th>状态</th><th>异常说明</th><th class="actions-heading">操作</th></tr></thead><tbody><tr v-for="item in current.items" :key="item.id"><td>{{ item.assetName }}</td><td><RouterLink v-if="item.runId" :to="`/scans/${item.runId}`">#{{ item.runId }}</RouterLink><span v-else>未启动</span></td><td class="mono version">{{ item.scanRef || '当前工作区' }}<div>{{ item.run?.scanCommit ?? '' }}</div></td><td><StatusBadge :value="item.status" /></td><td>{{ item.errorMessage ?? '—' }}</td><td class="actions-cell"><div class="row-actions" v-if="item.runId"><RouterLink class="row-action-link" :to="`/findings?runId=${item.runId}`">问题台账</RouterLink><a v-if="['COMPLETED','PARTIAL'].includes(item.status)" class="row-action-link" :href="api.reportUrl(item.runId, 'docx')">单资产报告</a><RouterLink v-if="['COMPLETED','PARTIAL'].includes(item.status)" class="row-action-link" :to="`/reports/${item.runId}/acceptance`">整改验收</RouterLink></div></td></tr></tbody></table></div></section></template><EmptyState v-else title="尚无扫描记录" description="关联资产的历史扫描与项目扫描会统一显示在这里。" />
    </template>
    <AppModal :open="linkOpen" title="关联已有资产" :busy="busy" @close="linkOpen = false"><form class="page-stack" @submit.prevent="attach"><div class="field"><label>未归属资产</label><select class="select" v-model="linkId"><option v-for="a in candidates" :key="a.id" :value="a.id">{{ a.projectName }} · {{ a.sourceType === 'JAR_UPLOAD' ? 'JAR' : '代码' }}</option></select></div><p v-if="!candidates.length">暂无可关联资产，可先登记仓库或上传 JAR。</p><div v-if="error" class="error-banner">{{ error }}</div><div class="form-actions"><button type="button" class="button secondary" :disabled="busy" @click="linkOpen = false">取消</button><button class="button primary" :disabled="busy || !linkId">确认关联</button></div></form></AppModal>
    <AppModal :open="editOpen" title="编辑业务项目" :busy="busy" @close="editOpen = false"><form class="page-stack" @submit.prevent="saveProject"><div class="field"><label>项目名称</label><input class="input" v-model.trim="editName" required maxlength="128" /></div><div class="field"><label>项目描述</label><textarea class="textarea" v-model="editDescription" maxlength="2000" /></div><div v-if="error" class="error-banner">{{ error }}</div><div class="form-actions"><button type="button" class="button secondary" :disabled="busy" @click="editOpen = false">取消</button><button class="button primary" :disabled="busy">保存</button></div></form></AppModal>
    <AppModal :open="scanOpen" :title="fullScope ? '扫描整个项目' : '扫描选中资产'" :busy="busy" @close="scanOpen = false"><form class="page-stack" @submit.prevent="start"><p>本轮扫描 {{ configAssets.length }} 个资产，统一全量扫描；超出并发上限的任务自动排队。</p><div class="form-grid"><div class="field"><label>扫描方式</label><select class="select" v-model="networkMode" :disabled="busy"><option value="LOCAL">本地扫描</option><option value="ONLINE">联网扫描</option></select></div><div class="field"><label>规则集</label><select class="select" v-model="ruleProfile" :disabled="busy"><option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.name }}</option></select></div></div><label v-if="networkMode === 'ONLINE' && configAssets.some(a => a.sourceType === 'GIT')" class="sync-option"><input v-model="refreshRemote" type="checkbox" :disabled="busy" /> 扫描前同步远程代码（可选，默认使用本地缓存）</label><section v-for="a in configAssets" :key="a.id" class="target-config"><strong>{{ a.projectName }}</strong><span v-if="a.sourceType === 'JAR_UPLOAD'" class="muted">JAR 全量 · {{ a.localPath?.split('/').pop() }}</span><template v-else><select class="select" v-model="targets[a.id]!.workspaceMode" :disabled="busy" :aria-label="`${a.projectName} 工作区`"><option v-if="a.sourceType === 'LOCAL'" value="CURRENT">当前工作区</option><option value="BRANCH_SNAPSHOT">分支快照</option></select><input v-if="targets[a.id]!.workspaceMode === 'BRANCH_SNAPSHOT'" class="input mono" v-model="targets[a.id]!.scanRef" :list="`branches-${a.id}`" :placeholder="a.defaultBranch ?? 'main'" maxlength="255" :disabled="busy" :aria-label="`${a.projectName} 分支`" /><datalist :id="`branches-${a.id}`"><option v-for="b in branches[a.id] ?? []" :key="b.name" :value="b.name" /></datalist></template></section><div v-if="error" class="error-banner">{{ error }}</div><div class="form-actions"><button type="button" class="button secondary" :disabled="busy" @click="scanOpen = false">取消</button><button class="button primary" :disabled="busy || !profiles.length">{{ busy ? '提交中…' : '提交扫描' }}</button></div></form></AppModal>
  </div>
</template>
<style scoped>.project-tabs{grid-template-columns:repeat(4,minmax(0,1fr))}.project-metrics{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));border:1px solid var(--line);border-radius:12px;background:var(--panel)}.project-metrics section{padding:20px;display:grid;gap:8px;border-right:1px solid var(--line)}.project-metrics section:last-child{border:0}.project-metrics span,.project-metrics small{color:var(--muted)}.project-metrics strong{font-size:30px;font-weight:600;font-variant-numeric:tabular-nums}.round-selector{display:flex;justify-content:space-between;align-items:end;gap:18px;padding:20px}.round-selector .field{flex:1}.version{max-width:260px;overflow-wrap:anywhere}.target-config{display:grid;gap:10px;border-bottom:1px solid var(--line);padding-bottom:16px}@media(max-width:800px){.project-tabs{grid-template-columns:repeat(2,minmax(0,1fr))}.project-metrics{grid-template-columns:1fr}.project-metrics section{border-right:0;border-bottom:1px solid var(--line)}.round-selector{align-items:stretch;flex-direction:column}}</style>

<style scoped>
.project-assets-table { min-width: 1320px; }
.project-assets-table th:first-child { width: 52px; }
.project-assets-table th:nth-child(2) { width: 210px; }
.project-assets-table th:nth-child(3) { width: 175px; }
.project-assets-table th:nth-child(5), .project-assets-table th:nth-child(6) { width: 125px; }
.project-assets-table th:last-child { width: 320px; }
.project-rounds-table { min-width: 1200px; }
.project-rounds-table th:nth-child(2) { width: 100px; }
.project-rounds-table th:nth-child(4) { width: 115px; }
.project-rounds-table th:last-child { width: 300px; }
</style>
