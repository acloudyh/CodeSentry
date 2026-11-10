<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api/codesentry'
import type { BranchView, RuleProfile, ScanMode, ScanProject, WorkspaceMode } from '@/types'

const sourceReportRunId = Number(useRoute().query.sourceReportRunId) || undefined
const router = useRouter()
const route = useRoute()
const projects = ref<ScanProject[]>([])
const branches = ref<BranchView[]>([])
const loadingBranches = ref(false)
const submitting = ref(false)
const error = ref('')
const networkMode = ref<'LOCAL' | 'ONLINE'>(route.query.networkMode === 'ONLINE' ? 'ONLINE' : 'LOCAL')
const source = ref<'repository' | 'jar'>(route.query.source === 'jar' ? 'jar' : 'repository')
const jarProjectId = ref(0)
const repositoryProjects = computed(() => projects.value.filter((project) => project.sourceType !== 'JAR_UPLOAD'))
const jarProjects = computed(() => projects.value.filter((project) => project.sourceType === 'JAR_UPLOAD'))
const selectedJar = computed(() => jarProjects.value.find((project) => project.id === jarProjectId.value))
function projectName(project: ScanProject) {
  return project.projectName.replace(/ · [0-9a-f]{8}-[0-9a-f-]{27}$/i, '')
}
async function submitJar() {
  if (!selectedJar.value || submitting.value) return
  submitting.value = true; error.value = ''
  try {
    const run = await api.startScan({ projectId: selectedJar.value.id, scanMode: 'FULL', workspaceMode: 'JAR_UPLOAD', refreshRemote: false, ruleProfile: 'COMMUNITY_GENERAL', networkMode: networkMode.value })
    await router.push(`/scans/${run.id}`)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '扫描启动失败' }
  finally { submitting.value = false }
}
const ruleProfiles = ref<RuleProfile[]>([])
const form = reactive({
  projectId: 0,
  scanMode: 'INCREMENTAL' as ScanMode,
  workspaceMode: 'BRANCH_SNAPSHOT' as WorkspaceMode,
  scanRef: '', baselineRef: '', refreshRemote: true,
  ruleProfile: 'LOCAL_OWASP',
})

const selectedProject = computed(() => projects.value.find((item) => item.id === form.projectId))
const canCurrentWorkspace = computed(() => selectedProject.value?.sourceType === 'LOCAL')

let branchRequest = 0
async function loadBranches(id: number) {
  const requestId = ++branchRequest
  branches.value = []
  if (!id || source.value !== 'repository') { loadingBranches.value = false; return }
  if (!canCurrentWorkspace.value && form.workspaceMode === 'CURRENT') form.workspaceMode = 'BRANCH_SNAPSHOT'
  loadingBranches.value = true; error.value = ''
  try {
    const result = await api.branches(id, false)
    if (requestId !== branchRequest || source.value !== 'repository') return
    branches.value = result
    const defaultBranch = selectedProject.value?.defaultBranch ?? 'main'
    form.baselineRef = result.some((item) => item.name === defaultBranch) ? defaultBranch : result[0]?.name ?? ''
    form.scanRef = result[0]?.name ?? ''
  } catch (reason) { if (requestId === branchRequest && source.value === 'repository') error.value = reason instanceof Error ? reason.message : '分支读取失败' }
  finally { if (requestId === branchRequest) loadingBranches.value = false }
}
watch(() => form.projectId, loadBranches)
watch(source, () => loadBranches(form.projectId))

async function submit() {
  submitting.value = true; error.value = ''
  try {
    await api.startScan({ ...form, networkMode: networkMode.value, refreshRemote: networkMode.value === 'ONLINE' && form.refreshRemote, sourceReportRunId, scanRef: form.workspaceMode === 'CURRENT' ? undefined : form.scanRef,
      baselineRef: form.scanMode === 'FULL' ? undefined : form.baselineRef })
    await router.push(sourceReportRunId ? `/reports/${sourceReportRunId}/acceptance` : '/scans')
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '扫描启动失败' } finally { submitting.value = false }
}

onMounted(async () => {
  try {
    [projects.value, ruleProfiles.value] = await Promise.all([api.projects(), api.ruleProfiles()])
    const requestedProfile = String(route.query.ruleProfile ?? '')
    if (ruleProfiles.value.some((item) => item.id === requestedProfile)) form.ruleProfile = requestedProfile
    if (sourceReportRunId) {
      const { source } = await api.reportAcceptance(sourceReportRunId)
      form.projectId = source.projectId
      form.ruleProfile = source.ruleProfile
      form.scanMode = 'FULL'
    } else {
      const requestedProject = projects.value.find((project) => project.id === Number(route.query.projectId))
      if (requestedProject) source.value = requestedProject.sourceType === 'JAR_UPLOAD' ? 'jar' : 'repository'
      form.projectId = requestedProject?.sourceType !== 'JAR_UPLOAD' && requestedProject ? requestedProject.id : repositoryProjects.value[0]?.id ?? 0
      jarProjectId.value = requestedProject?.sourceType === 'JAR_UPLOAD' ? requestedProject.id : jarProjects.value[0]?.id ?? 0
    }
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '项目或规则集加载失败' }
})
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>扫描任务配置</h2><p>选择已登记的代码仓库或 JAR 项目，发起安全扫描。</p></div><span class="section-code">扫描配置</span></section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="sourceReportRunId" class="notice">正在为报告 CSR-{{ String(sourceReportRunId).padStart(6, '0') }} 创建验收复扫。已锁定原项目、原规则集和全量模式，请选择整改后的代码。</div>
    <div v-if="!sourceReportRunId" class="segmented source-tabs" aria-label="扫描来源">
      <button type="button" :disabled="submitting" :class="{ active: source === 'repository' }" @click="source = 'repository'; error = ''">代码仓库扫描</button>
      <button type="button" :disabled="submitting" :class="{ active: source === 'jar' }" @click="source = 'jar'; error = ''">JAR 项目扫描</button>
    </div>
    <section class="panel panel-pad network-options" aria-label="扫描联网方式">
      <div><h3>扫描方式</h3><p class="muted">{{ networkMode === 'ONLINE' ? '本地扫描后，联网补查修复版本与 Java 要求；查询依据随任务保存。' : '使用本地规则、已缓存的漏洞库和已有修复资料，不联网补查修复方案。' }}</p></div>
      <div class="segmented"><button type="button" :disabled="submitting" :class="{ active: networkMode === 'LOCAL' }" @click="networkMode = 'LOCAL'">本地扫描</button><button type="button" :disabled="submitting" :class="{ active: networkMode === 'ONLINE' }" @click="networkMode = 'ONLINE'">联网扫描</button></div>
      <p v-if="networkMode === 'ONLINE'" class="muted">仅查询组件坐标、版本与漏洞编号，不上传源码或 JAR。联网失败时保留本地结果，并标明未核实项。</p>
    </section>
    <form v-if="source === 'jar'" class="panel panel-pad jar-panel" @submit.prevent="submitJar">
      <div class="jar-heading"><span class="section-code">JAR 安全扫描</span><h3>选择已有 JAR 项目</h3><p class="muted">使用项目中已保存的 JAR，检查依赖已知漏洞与业务字节码安全问题，无需重复上传。</p></div>
      <div class="form-grid">
        <div class="field span-2"><label for="jar-project">目标 JAR 项目</label><select id="jar-project" v-model.number="jarProjectId" class="select" :disabled="submitting" required><option :value="0" disabled>请选择 JAR 项目</option><option v-for="project in jarProjects" :key="project.id" :value="project.id">{{ projectName(project) }}</option></select><small v-if="!jarProjects.length">暂无 JAR 项目，请先新增并上传文件。</small></div>
        <div v-if="selectedJar" class="field span-2 jar-picker"><span class="muted">当前文件</span><strong class="jar-filename">{{ selectedJar.localPath?.split('/').pop() }}</strong><small>业务系统 Java 版本：{{ selectedJar.targetJavaVersion ? `Java ${selectedJar.targetJavaVersion}` : '未确认' }}。如需更新文件或项目信息，请到“扫描项目”中编辑。</small></div>
        <div class="field span-2 jar-checks"><div><strong>依赖已知漏洞</strong><p>识别 JAR 及内嵌依赖的组件漏洞。</p></div><div><strong>业务代码安全问题</strong><p>分析字节码中的注入、不安全调用等风险。</p></div></div>
        <div class="field span-2 notice">JAR 项目使用全量扫描。每次扫描固定使用创建任务时的文件版本，历史结果保留。</div>
        <div class="form-actions field span-2"><RouterLink :to="{ path: '/projects', query: { type: 'jar', new: 'jar' } }" class="button secondary">新增 JAR 项目</RouterLink><RouterLink to="/projects?type=jar" class="button secondary">管理 JAR 项目</RouterLink><button type="submit" class="button primary" :disabled="submitting || !selectedJar">{{ submitting ? '正在创建任务…' : networkMode === 'ONLINE' ? '发起联网扫描' : '发起本地扫描' }}</button></div>
      </div>
    </form>
    <form v-else class="panel panel-pad" @submit.prevent="submit">
      <div class="form-grid">
        <div class="field span-2"><label for="repository-project">目标项目</label><select id="repository-project" v-model.number="form.projectId" :disabled="Boolean(sourceReportRunId)" class="select" required><option :value="0" disabled>请选择仓库</option><option v-for="project in repositoryProjects" :key="project.id" :value="project.id">{{ project.projectName }} · {{ project.sourceType === 'LOCAL' ? '本地' : '远程' }}</option></select></div>
        <div class="field"><label>扫描模式</label><div class="segmented"><button type="button" :disabled="Boolean(sourceReportRunId)" :class="{active:form.scanMode==='FULL'}" @click="form.scanMode='FULL'">全量扫描</button><button type="button" :disabled="Boolean(sourceReportRunId)" :class="{active:form.scanMode==='INCREMENTAL'}" @click="form.scanMode='INCREMENTAL'">增量扫描</button></div></div>
        <div class="field"><label>代码读取方式</label><div class="segmented"><button type="button" :disabled="!canCurrentWorkspace" :class="{active:form.workspaceMode==='CURRENT'}" @click="form.workspaceMode='CURRENT'">当前工作区</button><button type="button" :class="{active:form.workspaceMode==='BRANCH_SNAPSHOT'}" @click="form.workspaceMode='BRANCH_SNAPSHOT'">分支快照</button></div></div>
        <div v-if="form.workspaceMode === 'BRANCH_SNAPSHOT'" class="field"><label>待扫描分支</label><select v-model="form.scanRef" class="select" required><option v-for="branch in branches" :key="branch.name" :value="branch.name">{{ branch.name }} · {{ branch.commit.slice(0,8) }}</option></select><small>{{ loadingBranches ? '正在读取分支…' : '通过 git archive 导出，不切换原仓库分支。' }}</small></div>
        <div v-if="form.scanMode === 'INCREMENTAL'" class="field"><label>基线分支</label><select v-model="form.baselineRef" class="select" required><option v-for="branch in branches" :key="branch.name" :value="branch.name">{{ branch.name }} · {{ branch.commit.slice(0,8) }}</option></select><small>实际对比起点为基线分支与扫描版本的共同祖先。</small></div>
        <div class="field span-2"><label for="rule-profile">扫描规则集</label><select id="rule-profile" v-model="form.ruleProfile" :disabled="Boolean(sourceReportRunId)" class="select" required><option v-for="profile in ruleProfiles" :key="profile.id" :value="profile.id">{{ profile.name }}{{ profile.offlineReady ? ' · 支持离线' : '' }}</option></select><small>{{ ruleProfiles.find((item) => item.id === form.ruleProfile)?.description }}</small></div>
        <div v-if="selectedProject?.sourceType === 'GIT' && networkMode === 'ONLINE'" class="field span-2"><label style="display:flex;gap:10px;align-items:center"><input v-model="form.refreshRemote" type="checkbox" /> 扫描前同步远程分支（git remote update --prune）</label></div>
        <div class="field span-2"><div v-if="sourceReportRunId" class="notice">复扫完成后，回到原报告的“整改验收”逐条查看结果。未再检出表示本次扫描未命中，仍需结合修改记录确认。</div><div v-else class="notice">精确增量会分别扫描当前版本和共同祖先快照（merge-base），通过稳定指纹识别新增、存量和已修复问题。第一版优先准确性，后续再增加快速增量缓存。</div></div>
        <div class="form-actions field span-2"><RouterLink to="/" class="button secondary">返回</RouterLink><button class="button primary" :disabled="submitting || !form.projectId">{{ submitting ? '正在创建任务…' : networkMode === 'ONLINE' ? '发起联网扫描' : '发起本地扫描' }}</button></div>
      </div>
    </form>
  </div>
</template>

<style scoped>
.network-options h3 { margin: 0 0 8px; }
.network-options .segmented { margin-top: 16px; max-width: 440px; }
.network-options p { margin-bottom: 0; line-height: 1.6; }
.source-tabs { align-self: flex-start; }
.jar-panel .field { align-content: start; }
.jar-panel .input, .jar-panel .select { height: 44px; min-height: 44px; }
.jar-heading { margin-bottom: 24px; }
.jar-heading h3 { margin: 12px 0 8px; }
.jar-picker { padding: 24px; border: 1px dashed var(--line, #aaa); border-radius: 12px; background: var(--surface-soft, #f7f8fa); }
.jar-picker input { max-width: 100%; padding: 12px 0; }
.jar-filename { overflow-wrap: anywhere; }
.jar-checks { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.jar-checks > div { padding: 16px; border: 1px solid var(--line, #ddd); border-radius: 10px; }
.jar-checks p { margin: 8px 0 0; color: var(--muted, #666); }
@media (max-width: 640px) { .jar-checks { grid-template-columns: 1fr; } }
</style>
