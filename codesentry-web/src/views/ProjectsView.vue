<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '@/api/codesentry'
import { formatChinaDate } from '@/utils/datetime'
import EmptyState from '@/components/ui/EmptyState.vue'
import type { ScanProject, SourceType } from '@/types'

const projects = ref<ScanProject[]>([])
const showForm = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const error = ref('')
const form = reactive({ projectName: '', sourceType: 'LOCAL' as SourceType, localPath: '', remoteUrl: '', defaultBranch: 'main' })

async function load() {
  try { projects.value = await api.projects() } catch (reason) { error.value = reason instanceof Error ? reason.message : '加载失败' }
}

function register() {
  editingId.value = null
  Object.assign(form, { projectName: '', sourceType: 'LOCAL', localPath: '', remoteUrl: '', defaultBranch: 'main' })
  showForm.value = true; error.value = ''
}

function edit(project: ScanProject) {
  editingId.value = project.id
  Object.assign(form, { projectName: project.projectName, sourceType: project.sourceType,
    localPath: project.localPath ?? '', remoteUrl: project.remoteUrl ?? '', defaultBranch: project.defaultBranch ?? '' })
  showForm.value = true; error.value = ''
  window.scrollTo({top:0, behavior:'smooth'})
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
    }
    if (editingId.value !== null) await api.updateProject(editingId.value, payload)
    else await api.createProject(payload)
    Object.assign(form, { projectName: '', localPath: '', remoteUrl: '', defaultBranch: 'main' })
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
      <div><h2>仓库列表</h2><p>登记已有本地仓库，或让 CodeSentry 维护一个远程 Git 镜像。</p></div>
      <button class="button primary" :disabled="saving" @click="showForm ? (showForm = false) : register()">{{ showForm ? '收起表单' : '+ 登记仓库' }}</button>
    </section>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <section v-if="showForm" class="panel panel-pad">
      <h3>{{ editingId !== null ? '编辑仓库' : '登记仓库' }}</h3>
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

    <section class="panel">
      <div v-if="projects.length" class="table-scroll">
        <table class="data-table">
          <thead><tr><th>编号 / 项目</th><th>来源</th><th>仓库位置</th><th>默认分支</th><th>登记时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="project in projects" :key="project.id">
              <td><span class="mono">P-{{ String(project.id).padStart(3, '0') }}</span><div style="margin-top:5px"><strong>{{ project.projectName }}</strong></div></td>
              <td><span class="status-badge"><i></i>{{ project.sourceType === 'LOCAL' ? '本地目录' : '远程镜像' }}</span></td>
              <td class="mono">{{ project.localPath ?? project.remoteUrl }}</td>
              <td class="mono">{{ project.defaultBranch ?? '-' }}</td>
              <td class="mono">{{ formatChinaDate(project.createdAt) }}</td>
              <td><div class="report-actions"><button class="button secondary compact" :disabled="saving" @click="edit(project)">编辑</button><button class="button danger compact" :disabled="saving" @click="remove(project)">移除</button></div></td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else title="还没有代码仓库" description="登记一个已经拉取到本机的 Git 目录，或者输入远程 Git 地址建立只读镜像。" />
    </section>
  </div>
</template>
