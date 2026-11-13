<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { repositorySyncLabel, repositorySyncBusy, repositoryReady } from '@/utils/labels'
import { api } from '@/api/codesentry'
import { useRoute, useRouter } from 'vue-router'
import { applicationTypeLabel, runtimeVersionLabel } from '@/utils/labels'
import { formatChinaTime } from '@/utils/datetime'
import { formatChinaDate } from '@/utils/datetime'
import EmptyState from '@/components/ui/EmptyState.vue'
import AppModal from '@/components/ui/AppModal.vue'
import AssetScanDialog from '@/components/AssetScanDialog.vue'
import JarUploadForm from '@/components/ui/JarUploadForm.vue'
import type { ApplicationType, GitValidationResult, ScanProject, ScanRun, SourceType } from '@/types'

const router = useRouter()
const route = useRoute()
const businessProjectId = computed(() => Number(route.query.businessProjectId) || null)
const unassignedOnly = computed(() => route.query.unassigned === '1')
async function associate(project: ScanProject) {
  if (!businessProjectId.value) return
  try { await api.attachAsset(businessProjectId.value, project.id) }
  catch (reason) { throw new Error(`资产已保存，但关联项目失败：${reason instanceof Error ? reason.message : '请在项目中重新关联'}`) }
}
const uploadOpen = ref(useRoute().query.new === 'jar')
const uploadBusy = ref(false)
const savedMessage = ref('')
async function uploaded(project: ScanProject) {
  uploadOpen.value = false
  try { await associate(project) } catch (reason) { error.value = (reason as Error).message }
  listType.value = 'jar'
  await router.replace({ path: '/projects', query: { ...route.query, new: undefined, type: 'jar' } })
  await load()
  savedMessage.value = `“${displayName(project)}”已保存。点击“发起扫描”即可使用该 JAR，无需再次上传。`
}
const jarEditing = ref<ScanProject | null>(null)
const replacementFile = ref<File | null>(null)
const jarName = ref('')
const jarJavaVersion = ref<number | null>(null)
const scanAfterSave = ref(false)
const projects = ref<ScanProject[]>([])
const listType = ref<'repository' | 'jar'>(useRoute().query.type === 'jar' ? 'jar' : 'repository')
const scopedProjects = computed(() => projects.value.filter(p => businessProjectId.value ? p.businessProjectId === businessProjectId.value : unassignedOnly.value ? !p.businessProjectId : true))
const repositories = computed(() => scopedProjects.value.filter((project) => project.sourceType !== 'JAR_UPLOAD'))
const jarUploads = computed(() => scopedProjects.value.filter((project) => project.sourceType === 'JAR_UPLOAD'))
const visibleProjects = computed(() => (listType.value === 'jar' ? jarUploads.value : repositories.value).filter(p => businessProjectId.value ? p.businessProjectId === businessProjectId.value : unassignedOnly.value ? !p.businessProjectId : true))
function displayName(project: ScanProject) {
  return project.sourceType === 'JAR_UPLOAD'
    ? project.projectName.replace(/ · [0-9a-f]{8}-[0-9a-f-]{27}$/i, '') : project.projectName
}
const showForm = ref(false)
const saving = ref(false)
const validatingGit = ref(false)
const gitValidation = ref<GitValidationResult | null>(null)
const gitValidationError = ref('')
let gitValidationRequest = 0
const editingId = ref<number | null>(null)
const error = ref('')
const syncingId = ref<number | null>(null)
const nodeVersions = ['14', '16', '18', '20', '22', '24']
const nodeVersionChoice = ref('')
const form = reactive({ projectName: '', applicationType: 'BACKEND' as ApplicationType, targetNodeVersion: '', gitAuthMode: 'DEFAULT' as 'DEFAULT' | 'PASSWORD', gitUsername: '', gitPassword: '', sourceType: 'LOCAL' as SourceType, localPath: '', remoteUrl: '', defaultBranch: 'main', targetJavaVersion: null as number | null })

watch(nodeVersionChoice, value => { if (value !== 'CUSTOM') form.targetNodeVersion = value }, { flush: 'sync' })

async function load() {
  try { projects.value = await api.projects() } catch (reason) { error.value = reason instanceof Error ? reason.message : '加载失败' }
}

function editJar(project: ScanProject) {
  showForm.value = false
  jarEditing.value = project
  jarName.value = displayName(project)
  jarJavaVersion.value = project.targetJavaVersion ?? null
  replacementFile.value = null
  scanAfterSave.value = false
  error.value = ''
}

function selectReplacement(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  replacementFile.value = null
  if (file && (!file.name.toLowerCase().endsWith('.jar') || !file.size || file.size > 2 * 1024 * 1024 * 1024)) {
    error.value = '请选择有效的 .jar 文件，大小不超过 2 GB（2048 MB）'
    input.value = ''
    return
  }
  error.value = ''
  replacementFile.value = file
  scanAfterSave.value = Boolean(file)
}

async function saveJar() {
  if (!jarEditing.value || saving.value) return
  saving.value = true; error.value = ''
  let saved = false
  try {
    const project = await api.updateJarProject(jarEditing.value.id, jarName.value.trim(), jarJavaVersion.value, replacementFile.value)
    saved = true
    jarEditing.value = null
    replacementFile.value = null
    await load()
    if (scanAfterSave.value) {
      startProjectScan(project)
    }
  } catch (reason) {
    const detail = reason instanceof Error ? reason.message : '保存失败'
    error.value = saved ? `项目已保存，但扫描未启动：${detail}。可点击“发起扫描”重试。` : detail
  } finally { saving.value = false }
}


function register() {
  nodeVersionChoice.value = ''
  jarEditing.value = null
  listType.value = 'repository'
  editingId.value = null
  Object.assign(form, { projectName: '', applicationType: 'BACKEND', targetNodeVersion: '', gitAuthMode: 'DEFAULT', gitUsername: '', gitPassword: '', sourceType: 'LOCAL', localPath: '', remoteUrl: '', defaultBranch: 'main', targetJavaVersion: null as number | null })
  showForm.value = true; error.value = ''
}

function edit(project: ScanProject) {
  jarEditing.value = null
  editingId.value = project.id
  Object.assign(form, { projectName: project.projectName, applicationType: project.applicationType ?? 'BACKEND', targetNodeVersion: project.targetNodeVersion ?? '', gitAuthMode: project.gitCredentialConfigured ? 'PASSWORD' : 'DEFAULT', gitUsername: project.gitUsername ?? '', gitPassword: '', sourceType: project.sourceType,
    localPath: project.localPath ?? '', remoteUrl: project.remoteUrl ?? '', defaultBranch: project.defaultBranch ?? '', targetJavaVersion: project.targetJavaVersion ?? null })
  nodeVersionChoice.value = project.targetNodeVersion ? nodeVersions.includes(project.targetNodeVersion) ? project.targetNodeVersion : 'CUSTOM' : ''
  showForm.value = true; error.value = ''
}

async function save() {
  saving.value = true; error.value = ''
  try {
    const payload = {
      projectName: form.projectName,
      sourceType: form.sourceType,
      localPath: form.sourceType === 'LOCAL' ? form.localPath : undefined,
      remoteUrl: form.sourceType === 'GIT' ? form.remoteUrl : undefined,
      defaultBranch: form.defaultBranch,
      applicationType: form.applicationType,
      targetJavaVersion: form.applicationType === 'FRONTEND' ? null : form.targetJavaVersion,
      targetNodeVersion: form.applicationType === 'BACKEND' ? null : form.targetNodeVersion.trim() || null,
      gitUsername: form.sourceType === 'GIT' && form.gitAuthMode === 'PASSWORD' ? form.gitUsername.trim() : undefined,
      gitPassword: form.sourceType === 'GIT' && form.gitAuthMode === 'PASSWORD' && form.gitPassword ? form.gitPassword : undefined,
      clearGitCredentials: form.sourceType === 'GIT' && form.gitAuthMode === 'DEFAULT',

    }
    if (editingId.value !== null) await api.updateProject(editingId.value, payload)
    else {
      const created = await api.createProject(payload)
      try { await associate(created) } catch (reason) { showForm.value = false; await load(); throw reason }
    }
    savedMessage.value = form.sourceType === 'GIT' ? '仓库信息已保存；代码在后台拉取，可在列表查看进度或重试。' : '仓库信息已保存。'
    Object.assign(form, { projectName: '', gitPassword: '', gitUsername: '', localPath: '', remoteUrl: '', defaultBranch: 'main', targetJavaVersion: null as number | null })
    showForm.value = false
    editingId.value = null
    await load()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '保存失败' } finally { saving.value = false }
}

const editingProject = computed(() => projects.value.find(p => p.id === editingId.value))
const canKeepCredentials = computed(() => !!editingProject.value?.gitCredentialConfigured && form.remoteUrl.trim() === editingProject.value.remoteUrl && form.gitUsername.trim() === editingProject.value.gitUsername)
async function validateGit() {
  if (saving.value || validatingGit.value) return
  gitValidation.value = null; gitValidationError.value = ''; error.value = ''
  if (!form.remoteUrl.trim()) { gitValidationError.value = '请先填写 Git 仓库地址'; return }
  const usePassword = form.gitAuthMode === 'PASSWORD'
  if (usePassword && !form.gitUsername.trim()) { gitValidationError.value = '请先填写 Git 账号'; return }
  if (usePassword && !form.gitPassword && !canKeepCredentials.value) { gitValidationError.value = '请先填写 Git 密码或访问令牌'; return }
  validatingGit.value = true
  const requestId = ++gitValidationRequest
  try {
    const result = await api.validateGit({
      remoteUrl: form.remoteUrl.trim(),
      gitUsername: usePassword ? form.gitUsername.trim() : undefined,
      gitPassword: usePassword && form.gitPassword ? form.gitPassword : undefined,
      projectId: usePassword && !form.gitPassword && canKeepCredentials.value ? editingId.value ?? undefined : undefined,
      useStoredCredentials: usePassword && !form.gitPassword && canKeepCredentials.value,
    })
    if (requestId === gitValidationRequest) gitValidation.value = result
  } catch (reason) { if (requestId === gitValidationRequest) gitValidationError.value = reason instanceof Error ? reason.message : 'Git 校验失败' }
  finally { validatingGit.value = false }
}
watch(() => [form.sourceType, form.remoteUrl, form.gitUsername, form.gitPassword, form.gitAuthMode, editingId.value], () => { gitValidationRequest++; gitValidation.value = null; gitValidationError.value = '' })

async function syncCode(project: ScanProject) {
  if (syncingId.value !== null || saving.value) return
  syncingId.value = project.id; error.value = ''; savedMessage.value = `正在同步“${displayName(project)}”，请稍候…`
  try {
    await api.syncProject(project.id)
    await load()
    savedMessage.value = `“${displayName(project)}”已提交后台拉取，请在列表查看状态。`
  } catch (reason) { savedMessage.value = ''; error.value = reason instanceof Error ? reason.message : '同步失败'; await load() }
  finally { syncingId.value = null }
}
watch(showForm, value => { if (!value) form.gitPassword = '' })

const scanAsset = ref<ScanProject | null>(null)
const scanNetworkMode = ref<'LOCAL' | 'ONLINE'>('LOCAL'), scanRuleProfile = ref(''), scanSourceReportId = ref<number>()
let scanRouteRequest = 0
function startProjectScan(project: ScanProject) {
  scanRouteRequest++
  if (!repositoryReady(project)) { error.value = '代码尚未就绪，请等待拉取完成。'; return }
  scanNetworkMode.value = route.query.networkMode === 'ONLINE' ? 'ONLINE' : 'LOCAL'
  scanRuleProfile.value = String(route.query.ruleProfile ?? ''); scanSourceReportId.value = undefined
  scanAsset.value = project
}
async function clearScanQuery() {
  if(route.query.scan || route.query.sourceReportRunId || route.query.projectId) {
    const query = { ...route.query }; for(const key of ['scan','sourceReportRunId','projectId','source','networkMode','ruleProfile']) delete query[key]
    await router.replace({ path: '/projects', query })
  }
}
async function closeScan() { scanRouteRequest++; scanAsset.value = null; scanSourceReportId.value = undefined; await clearScanQuery() }
async function scanStarted(run: ScanRun) {
  const reportId = scanSourceReportId.value
  scanAsset.value = null; scanSourceReportId.value = undefined
  await clearScanQuery()
  await router.push(reportId ? `/reports/${reportId}/acceptance` : `/scans/${run.id}`)
}
async function openScanFromRoute() {
  if (route.query.scan !== '1') return
  const token = ++scanRouteRequest
  try {
    const reportId = Number(route.query.sourceReportRunId) || undefined
    let assetId = Number(route.query.projectId)
    let profile = String(route.query.ruleProfile ?? '')
    if (reportId) { const data = await api.reportAcceptance(reportId); if(token !== scanRouteRequest) return; assetId = data.source.projectId; profile = data.source.ruleProfile }
    const asset = projects.value.find(p => p.id === assetId)
    if (!asset) { if(assetId) error.value = '扫描资产不存在或已移除'; else savedMessage.value = '请选择下面的资产，点击“发起扫描”配置任务。'; return }
    listType.value = asset.sourceType === 'JAR_UPLOAD' ? 'jar' : 'repository'
    startProjectScan(asset); scanRuleProfile.value = profile; scanSourceReportId.value = reportId
  } catch(e) { error.value = (e as Error).message }
}
watch(() => [route.query.scan,route.query.projectId,route.query.sourceReportRunId], () => { if(projects.value.length) void openScanFromRoute() })

async function remove(project: ScanProject) {
  if (!window.confirm(`确认从 CodeSentry 删除“${project.projectName}”？不会删除你的本地源码。`)) return
  try { await api.deleteProject(project.id); await load() } catch (reason) { error.value = reason instanceof Error ? reason.message : '删除失败' }
}

watch(() => route.query.type, value => { listType.value = value === 'jar' ? 'jar' : 'repository' })
let syncTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => { syncTimer = setInterval(async () => { if (projects.value.some(repositorySyncBusy)) { try { await load() } catch { /* keep last visible status */ } } }, 3000) })
onBeforeUnmount(() => { if (syncTimer) clearInterval(syncTimer) })
onMounted(async () => { await load(); if (route.query.register === '1') register(); await openScanFromRoute() })
</script>

<template>
  <div class="page-stack">
    <AssetScanDialog v-if="scanAsset" :key="`${scanAsset.id}-${scanSourceReportId ?? 0}`" :asset="scanAsset" :initial-network-mode="scanNetworkMode" :initial-rule-profile="scanRuleProfile" :source-report-run-id="scanSourceReportId" @close="closeScan" @started="scanStarted" />
    <section class="section-heading">
      <div><h2>扫描资产</h2><p>统一管理本地代码仓库、远程 Git 仓库和上传的 JAR 包。</p></div>
      <div class="report-actions"><button type="button" class="button secondary" :disabled="saving" @click="uploadOpen = true">+ 新增 JAR 项目</button><button class="button primary" :disabled="saving" @click="register">+ 登记仓库</button></div>
    </section>

    <div v-if="businessProjectId" class="notice"><RouterLink :to="`/business-projects/${businessProjectId}`">← 返回业务项目</RouterLink> · 当前仅展示该项目资产，新登记资产自动关联到项目。</div>
    <div v-if="unassignedOnly" class="notice">当前仅展示未归属业务项目的资产。</div>
    <div v-if="savedMessage" class="notice" role="status">{{ savedMessage }}</div>
    <div v-if="error && !showForm && !jarEditing" class="error-banner">{{ error }}</div>

    <AppModal :open="uploadOpen" title="新增 JAR 项目" :busy="uploadBusy" @close="uploadOpen = false">
      <JarUploadForm v-if="uploadOpen" @busy="uploadBusy = $event" @created="uploaded" @cancel="uploadOpen = false" />
    </AppModal>

    <AppModal :open="Boolean(jarEditing)" title="编辑 JAR 项目" :busy="saving" @close="jarEditing = null">
      <section v-if="jarEditing" class="jar-edit-panel">
      <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
      <form class="form-grid" @submit.prevent="saveJar">
        <div class="field"><label for="edit-jar-name">项目名称</label><input id="edit-jar-name" v-model.trim="jarName" class="input" required maxlength="80" :disabled="saving" /></div>
        <div class="field"><label for="edit-jar-java">业务系统 Java 版本</label><select id="edit-jar-java" v-model="jarJavaVersion" class="select" :disabled="saving"><option :value="null">未确认</option><option v-for="version in [8, 11, 17, 21, 25]" :key="version" :value="version">Java {{ version }}</option></select></div>
        <div class="field span-2"><label for="edit-jar-file">替换 JAR 文件（选填）</label><small>当前文件：{{ jarEditing.localPath?.split('/').pop() }}</small><input id="edit-jar-file" type="file" accept=".jar" :disabled="saving" @change="selectReplacement" /><small>{{ replacementFile ? `已选择：${replacementFile.name} · ${(replacementFile.size / 1024 / 1024).toFixed(1)} MB` : '不选择文件则保留当前 JAR，仅保存项目信息。' }}</small></div>
        <div class="field span-2 notice">替换文件不会删除旧版本、历史扫描结果或报告。新扫描会使用替换后的 JAR。</div>
        <div class="field span-2"><label><input v-model="scanAfterSave" type="checkbox" :disabled="saving" /> 保存后启动全量扫描</label></div>
        <div class="form-actions field span-2"><button type="button" class="button secondary" :disabled="saving" @click="jarEditing = null">取消</button><button class="button primary" :disabled="saving">{{ saving ? '正在保存…' : scanAfterSave ? '保存并启动扫描' : '保存修改' }}</button></div>
      </form>
    </section>
    </AppModal>

    <AppModal :open="showForm" :title="editingId !== null ? '编辑仓库' : '登记仓库'" :busy="saving || validatingGit" @close="showForm = false">
      <section v-if="showForm" class="repository-edit-panel">
      <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
      <form class="form-grid" @submit.prevent="save">
        <div class="field span-2">
          <label>仓库来源</label>
          <div class="segmented">
            <button type="button" :disabled="editingId !== null || saving || validatingGit" :class="{ active: form.sourceType === 'LOCAL' }" @click="form.sourceType = 'LOCAL'">已有本地目录</button>
            <button type="button" :disabled="editingId !== null || saving || validatingGit" :class="{ active: form.sourceType === 'GIT' }" @click="form.sourceType = 'GIT'">远程 Git 地址</button>
          </div>
        </div>
        <div class="field"><label for="project-name">项目名称</label><input id="project-name" v-model.trim="form.projectName" :disabled="saving" class="input" required maxlength="128" placeholder="例如：订单中心" /></div>
        <div class="field"><label for="project-branch">默认基线分支</label><input id="project-branch" v-model.trim="form.defaultBranch" :disabled="saving" class="input" placeholder="main" /></div>
        <div class="field span-2"><label for="project-application">应用类型</label><select id="project-application" v-model="form.applicationType" class="select" :disabled="saving"><option value="FRONTEND">前端（Node.js）</option><option value="BACKEND">后端（Java）</option><option value="FULL_STACK">前后端混合</option></select></div>
        <div v-if="form.applicationType !== 'FRONTEND'" class="field" :class="{ 'span-2': form.applicationType === 'BACKEND' }"><label for="project-java">业务系统 Java 版本</label><select id="project-java" v-model="form.targetJavaVersion" class="select" :disabled="saving"><option :value="null">未确认</option><option v-for="version in [8, 11, 17, 21, 25]" :key="version" :value="version">Java {{ version }}</option></select><small>用于筛选 Java 依赖整改建议的兼容目标。</small></div>
        <div v-if="form.applicationType !== 'BACKEND'" class="field" :class="{ 'span-2': form.applicationType === 'FRONTEND' }"><label for="project-node">业务系统 Node.js 版本</label><select id="project-node" v-model="nodeVersionChoice" class="select" :disabled="saving"><option value="">未确认</option><option v-for="version in nodeVersions" :key="version" :value="version">Node.js {{ version }}</option><option value="CUSTOM">自定义版本</option></select><template v-if="nodeVersionChoice === 'CUSTOM'"><label for="project-node-custom">自定义 Node.js 版本</label><input id="project-node-custom" v-model.trim="form.targetNodeVersion" class="input" maxlength="32" placeholder="例如 18.20.8、20.19.0" required :disabled="saving" /></template><small>填写业务系统实际使用的 Node.js 版本。</small></div>
        <div v-if="form.sourceType === 'LOCAL'" class="field span-2">
          <label for="project-path">本地 Git 仓库绝对路径</label><input id="project-path" v-model.trim="form.localPath" :disabled="saving" class="input mono" required placeholder="/Users/neo/code/project-a" />
          <small>CodeSentry 不会切换分支或执行 git pull；当前目录模式可以审查未提交代码。</small>
        </div>
        <div v-else class="field span-2">
          <label for="project-remote">Git 仓库地址</label><div class="git-url-row"><input id="project-remote" v-model.trim="form.remoteUrl" :disabled="saving || validatingGit" class="input mono" required placeholder="git@gitlab.example.com:group/project.git" /><button type="button" class="button secondary" :disabled="saving || validatingGit" @click="validateGit">{{ validatingGit ? '正在校验…' : '校验 Git' }}</button></div>
          <small>支持 HTTP/HTTPS 或 SSH 地址；请勿把账号密码拼在仓库地址中。</small>
        </div>
        <template v-if="form.sourceType === 'GIT'">
          <div class="field span-2"><label for="git-auth-mode">Git 认证方式</label><select id="git-auth-mode" v-model="form.gitAuthMode" class="select" :disabled="saving || validatingGit"><option value="DEFAULT">无需账号密码 / 使用本机凭据或 SSH</option><option value="PASSWORD">账号密码 / 访问令牌（HTTP/HTTPS）</option></select></div>
          <template v-if="form.gitAuthMode === 'PASSWORD'">
            <div class="field"><label for="git-username">Git 账号</label><input id="git-username" v-model="form.gitUsername" class="input" autocomplete="username" maxlength="256" required :disabled="saving || validatingGit" /></div>
            <div class="field"><label for="git-password">Git 密码 / 访问令牌</label><input id="git-password" v-model="form.gitPassword" type="password" class="input" autocomplete="new-password" maxlength="4000" :required="!canKeepCredentials" :placeholder="canKeepCredentials ? '已配置，留空保留原密码或令牌' : '填写密码或访问令牌'" :disabled="saving || validatingGit" /><small>凭据加密保存，用于首次拉取及后续代码同步；修改账号或仓库地址后需重新填写。</small></div>
          </template>
          <div v-if="validatingGit" class="field span-2 notice" role="status">正在校验 Git 连接、证书及仓库读取权限，不下载代码、不保存账号密码。</div>
          <div v-if="gitValidationError" class="field span-2 error-banner" role="alert">{{ gitValidationError }}</div>
          <div v-if="gitValidation" class="field span-2 git-check-result" :class="gitValidation.valid ? 'notice' : 'error-banner'" :role="gitValidation.valid ? 'status' : 'alert'">
            <strong>{{ gitValidation.message }}</strong>
            <span v-if="gitValidation.defaultBranch">默认分支：{{ gitValidation.defaultBranch }} <button v-if="gitValidation.defaultBranch !== form.defaultBranch" type="button" class="row-action-link" :disabled="saving" @click="form.defaultBranch = gitValidation!.defaultBranch!">使用此分支</button></span>
          </div>
          <div v-if="editingProject?.managedPath" class="field span-2"><label>本地代码缓存</label><span class="mono">{{ editingProject.managedPath }}</span><small>最近成功同步：{{ formatChinaTime(editingProject.lastSyncedAt) }}。扫描默认复用本地缓存，需要时点击列表中的“同步代码”。</small></div>
        </template>
        <div class="field span-2"><div class="notice">{{ editingId !== null ? '编辑不切换仓库来源、不改写历史扫描记录。有扫描或工具审核执行时暂不能保存；修改远程地址会创建新的镜像。' : '远程仓库登记后在后台拉取并持久化到本地，保存无需等待；后续扫描默认使用缓存。需要更新代码时可手动同步，或选择扫描前同步，无需每次重新克隆。' }}</div></div>
        <div class="form-actions field span-2"><button type="button" class="button secondary" :disabled="saving || validatingGit" @click="showForm = false">取消</button><button class="button primary" :disabled="saving || validatingGit">{{ saving ? '正在保存…' : editingId !== null ? '保存修改' : '确认登记' }}</button></div>
      </form>
    </section>
    </AppModal>

    <div class="segmented" aria-label="项目类型">
      <button type="button" :class="{ active: listType === 'repository' }" @click="listType = 'repository'">代码仓库（{{ repositories.length }}）</button>
      <button type="button" :class="{ active: listType === 'jar' }" @click="listType = 'jar'; showForm = false">JAR 项目（{{ jarUploads.length }}）</button>
    </div>
    <div v-if="listType === 'jar'" class="notice">新增时上传 JAR，发起扫描时直接使用已保存文件；通过“编辑”替换版本，历史扫描结果会保留。</div>
    <section class="panel">
      <div v-if="visibleProjects.length" class="table-scroll">
        <table class="data-table projects-table" :class="{ 'repository-table': listType !== 'jar' }">
          <colgroup><col style="width:185px" /><col style="width:100px" /><col v-if="listType !== 'jar'" style="width:130px" /><col style="width:280px" /><col v-if="listType !== 'jar'" style="width:90px" /><col style="width:105px" /><col v-if="listType !== 'jar'" style="width:135px" /><col style="width:340px" /></colgroup>
          <thead><tr><th>编号 / 项目</th><th>来源</th><th v-if="listType !== 'jar'">应用 / 运行环境</th><th>{{ listType === 'jar' ? '当前 JAR 文件' : '仓库位置' }}</th><th v-if="listType !== 'jar'">默认分支</th><th>登记时间</th><th v-if="listType !== 'jar'">代码同步</th><th class="actions-heading">操作</th></tr></thead>
          <tbody>
            <tr v-for="project in visibleProjects" :key="project.id">
              <td><span class="mono">P-{{ String(project.id).padStart(3, '0') }}</span><div style="margin-top:5px"><strong>{{ displayName(project) }}</strong></div></td>
              <td><span class="status-badge"><i></i>{{ project.sourceType === 'JAR_UPLOAD' ? 'JAR 上传' : project.sourceType === 'LOCAL' ? '本地目录' : '远程镜像' }}</span></td>
              <td v-if="listType !== 'jar'"><strong>{{ applicationTypeLabel(project.applicationType) }}</strong><div class="mono">{{ runtimeVersionLabel(project) }}</div></td>
              <td class="mono"><span class="repository-location" :title="project.sourceType === 'JAR_UPLOAD' ? project.localPath?.split('/').pop() : project.localPath ?? project.remoteUrl">{{ project.sourceType === 'JAR_UPLOAD' ? project.localPath?.split('/').pop() : project.localPath ?? project.remoteUrl }}</span><small v-if="project.sourceType === 'GIT'" class="cache-location" :title="project.managedPath">缓存：{{ project.managedPath }}</small></td>
              <td v-if="listType !== 'jar'" class="mono">{{ project.defaultBranch ?? '-' }}</td>
              <td class="mono">{{ formatChinaDate(project.createdAt) }}</td>
              <td v-if="listType !== 'jar'"><template v-if="project.sourceType === 'GIT'"><span :class="{ 'danger-text': project.lastSyncStatus === 'FAILED' }" :title="project.lastSyncMessage">{{ repositorySyncLabel(project) }}</span><small class="cache-location">{{ formatChinaTime(project.lastSyncedAt) }}</small></template><span v-else class="muted">本地目录</span></td>
              <td class="actions-cell"><div class="row-actions">
                <button v-if="project.sourceType === 'GIT'" type="button" class="row-action-link" :disabled="saving || syncingId !== null || repositorySyncBusy(project)" @click="syncCode(project)">{{ repositorySyncBusy(project) ? repositorySyncLabel(project) : project.lastSyncStatus === 'FAILED' ? '重试拉取' : '同步代码' }}</button>
                <button type="button" class="button primary compact" :disabled="saving || syncingId !== null || !repositoryReady(project)" @click="startProjectScan(project)">发起扫描</button>
                <button type="button" class="row-action-link" :disabled="saving || syncingId !== null" @click="project.sourceType === 'JAR_UPLOAD' ? editJar(project) : edit(project)">编辑</button>
                <button type="button" class="row-action-link danger" :disabled="saving || syncingId !== null" @click="remove(project)">删除</button>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else :title="listType === 'jar' ? '还没有 JAR 项目' : '还没有代码仓库'" :description="listType === 'jar' ? '点击“新增 JAR 项目”上传文件，保存后即可选择该项目发起扫描。' : '当前环境未登记代码仓库；JAR 项目单独展示。'" />
    </section>
  </div>
</template>

<style scoped>
.projects-table { min-width: 1040px; table-layout:fixed; }
.repository-table { min-width:1380px; }
.repository-location,.cache-location{display:block;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.cache-location{margin-top:6px;color:var(--muted)}
.git-url-row{display:flex;gap:10px;align-items:center}.git-url-row .input{flex:1;min-width:0}.git-url-row .button{flex-shrink:0}.git-check-result{display:grid;gap:6px}@media(max-width:640px){.git-url-row{align-items:stretch;flex-direction:column}}
.actions-heading { text-align: right; }
.projects-table td:nth-last-child(2) { white-space: nowrap; }
.repository-edit-panel .field, .jar-edit-panel .field { align-content: start; }
.repository-edit-panel .input, .repository-edit-panel .select, .jar-edit-panel .input, .jar-edit-panel .select { height: 44px; min-height: 44px; }
</style>

<style scoped>.repository-unready{opacity:.5;cursor:not-allowed}</style>
