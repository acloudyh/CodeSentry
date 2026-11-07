<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '@/api/codesentry'
import { useRoute, useRouter } from 'vue-router'
import { formatChinaDate } from '@/utils/datetime'
import EmptyState from '@/components/ui/EmptyState.vue'
import AppModal from '@/components/ui/AppModal.vue'
import JarUploadForm from '@/components/ui/JarUploadForm.vue'
import type { ScanProject, SourceType } from '@/types'

const router = useRouter()
const uploadOpen = ref(useRoute().query.new === 'jar')
const uploadBusy = ref(false)
const savedMessage = ref('')
async function uploaded(project: ScanProject) {
  uploadOpen.value = false
  listType.value = 'jar'
  await router.replace({ path: '/projects', query: { type: 'jar' } })
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
const repositories = computed(() => projects.value.filter((project) => project.sourceType !== 'JAR_UPLOAD'))
const jarUploads = computed(() => projects.value.filter((project) => project.sourceType === 'JAR_UPLOAD'))
const visibleProjects = computed(() => listType.value === 'jar' ? jarUploads.value : repositories.value)
function displayName(project: ScanProject) {
  return project.sourceType === 'JAR_UPLOAD'
    ? project.projectName.replace(/ · [0-9a-f]{8}-[0-9a-f-]{27}$/i, '') : project.projectName
}
const showForm = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const error = ref('')
const form = reactive({ projectName: '', sourceType: 'LOCAL' as SourceType, localPath: '', remoteUrl: '', defaultBranch: 'main', targetJavaVersion: null as number | null })

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
      const run = await api.startScan({ projectId: project.id, scanMode: 'FULL', workspaceMode: 'JAR_UPLOAD', refreshRemote: false, ruleProfile: 'COMMUNITY_GENERAL' })
      await router.push(`/scans/${run.id}`)
    }
  } catch (reason) {
    const detail = reason instanceof Error ? reason.message : '保存失败'
    error.value = saved ? `项目已保存，但扫描未启动：${detail}。可点击“发起扫描”重试。` : detail
  } finally { saving.value = false }
}


function register() {
  jarEditing.value = null
  listType.value = 'repository'
  editingId.value = null
  Object.assign(form, { projectName: '', sourceType: 'LOCAL', localPath: '', remoteUrl: '', defaultBranch: 'main', targetJavaVersion: null as number | null })
  showForm.value = true; error.value = ''
}

function edit(project: ScanProject) {
  jarEditing.value = null
  editingId.value = project.id
  Object.assign(form, { projectName: project.projectName, sourceType: project.sourceType,
    localPath: project.localPath ?? '', remoteUrl: project.remoteUrl ?? '', defaultBranch: project.defaultBranch ?? '', targetJavaVersion: project.targetJavaVersion ?? null })
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
      targetJavaVersion: form.targetJavaVersion,
    }
    if (editingId.value !== null) await api.updateProject(editingId.value, payload)
    else await api.createProject(payload)
    Object.assign(form, { projectName: '', localPath: '', remoteUrl: '', defaultBranch: 'main', targetJavaVersion: null as number | null })
    showForm.value = false
    editingId.value = null
    await load()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '保存失败' } finally { saving.value = false }
}

async function remove(project: ScanProject) {
  if (!window.confirm(`确认从 CodeSentry 移除“${project.projectName}”？不会删除你的本地源码。`)) return
  try { await api.deleteProject(project.id); await load() } catch (reason) { error.value = reason instanceof Error ? reason.message : '删除失败' }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <section class="section-heading">
      <div><h2>扫描项目</h2><p>统一管理本地代码仓库、远程 Git 仓库和上传的 JAR 包。</p></div>
      <div class="report-actions"><button type="button" class="button secondary" :disabled="saving" @click="uploadOpen = true">+ 新增 JAR 项目</button><button class="button primary" :disabled="saving" @click="register">+ 登记仓库</button></div>
    </section>

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

    <AppModal :open="showForm" :title="editingId !== null ? '编辑仓库' : '登记仓库'" :busy="saving" @close="showForm = false">
      <section v-if="showForm" class="repository-edit-panel">
      <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
      <form class="form-grid" @submit.prevent="save">
        <div class="field span-2">
          <label>仓库来源</label>
          <div class="segmented">
            <button type="button" :disabled="editingId !== null || saving" :class="{ active: form.sourceType === 'LOCAL' }" @click="form.sourceType = 'LOCAL'">已有本地目录</button>
            <button type="button" :disabled="editingId !== null || saving" :class="{ active: form.sourceType === 'GIT' }" @click="form.sourceType = 'GIT'">远程 Git 地址</button>
          </div>
        </div>
        <div class="field"><label for="project-name">项目名称</label><input id="project-name" v-model.trim="form.projectName" :disabled="saving" class="input" required maxlength="128" placeholder="例如：订单中心" /></div>
        <div class="field"><label for="project-branch">默认基线分支</label><input id="project-branch" v-model.trim="form.defaultBranch" :disabled="saving" class="input" placeholder="main" /></div>
        <div class="field span-2"><label for="project-java">业务系统 Java 版本</label><select id="project-java" v-model="form.targetJavaVersion" class="select" :disabled="saving"><option :value="null">未确认</option><option v-for="version in [8, 11, 17, 21, 25]" :key="version" :value="version">Java {{ version }}</option></select><small>用于筛选整改报告的依赖升级目标；填写被扫描业务系统的版本。</small></div>
        <div v-if="form.sourceType === 'LOCAL'" class="field span-2">
          <label for="project-path">本地 Git 仓库绝对路径</label><input id="project-path" v-model.trim="form.localPath" :disabled="saving" class="input mono" required placeholder="/Users/neo/code/project-a" />
          <small>CodeSentry 不会切换分支或执行 git pull；当前目录模式可以审查未提交代码。</small>
        </div>
        <div v-else class="field span-2">
          <label for="project-remote">Git 仓库地址</label><input id="project-remote" v-model.trim="form.remoteUrl" :disabled="saving" class="input mono" required placeholder="git@gitlab.example.com:group/project.git" />
          <small>优先使用 SSH 和本机 ssh-agent。产品不保存私钥，也不调用 GitLab API。</small>
        </div>
        <div class="field span-2"><div class="notice">{{ editingId !== null ? '编辑不切换仓库来源、不改写历史扫描记录。有扫描或工具审核执行时暂不能保存；修改远程地址会创建新的镜像。' : '远程仓库登记时会执行一次镜像克隆，耗时取决于仓库大小。扫描时只按需导出指定提交，不创建额外工作树。' }}</div></div>
        <div class="form-actions field span-2"><button type="button" class="button secondary" :disabled="saving" @click="showForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? '正在保存…' : editingId !== null ? '保存修改' : '确认登记' }}</button></div>
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
        <table class="data-table">
          <thead><tr><th>编号 / 项目</th><th>来源</th><th>{{ listType === 'jar' ? '当前 JAR 文件' : '仓库位置' }}</th><th v-if="listType !== 'jar'">默认分支</th><th>登记时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="project in visibleProjects" :key="project.id">
              <td><span class="mono">P-{{ String(project.id).padStart(3, '0') }}</span><div style="margin-top:5px"><strong>{{ displayName(project) }}</strong></div></td>
              <td><span class="status-badge"><i></i>{{ project.sourceType === 'JAR_UPLOAD' ? 'JAR 上传' : project.sourceType === 'LOCAL' ? '本地目录' : '远程镜像' }}</span></td>
              <td class="mono">{{ project.sourceType === 'JAR_UPLOAD' ? project.localPath?.split('/').pop() : project.localPath ?? project.remoteUrl }}</td>
              <td v-if="listType !== 'jar'" class="mono">{{ project.defaultBranch ?? '-' }}</td>
              <td class="mono">{{ formatChinaDate(project.createdAt) }}</td>
              <td><div class="report-actions"><button class="button secondary compact" :disabled="saving" @click="project.sourceType === 'JAR_UPLOAD' ? editJar(project) : edit(project)">编辑</button><RouterLink class="button secondary compact" :to="{ path: '/scans/new', query: { source: project.sourceType === 'JAR_UPLOAD' ? 'jar' : 'repository', projectId: project.id } }">发起扫描</RouterLink><button class="button danger compact" :disabled="saving" @click="remove(project)">移除</button></div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else :title="listType === 'jar' ? '还没有 JAR 项目' : '还没有代码仓库'" :description="listType === 'jar' ? '点击“新增 JAR 项目”上传文件，保存后即可选择该项目发起扫描。' : '当前环境未登记代码仓库；JAR 项目单独展示。'" />
    </section>
  </div>
</template>

<style scoped>
.repository-edit-panel .field, .jar-edit-panel .field { align-content: start; }
.repository-edit-panel .input, .repository-edit-panel .select, .jar-edit-panel .input, .jar-edit-panel .select { height: 44px; min-height: 44px; }
</style>
