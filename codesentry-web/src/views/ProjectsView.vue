<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '@/api/codesentry'
import EmptyState from '@/components/ui/EmptyState.vue'
import type { ScanProject, SourceType } from '@/types'

const projects = ref<ScanProject[]>([])
const showForm = ref(false)
const saving = ref(false)
const error = ref('')
const form = reactive({ projectName: '', sourceType: 'LOCAL' as SourceType, localPath: '', remoteUrl: '', defaultBranch: 'main' })

async function load() {
  try { projects.value = await api.projects() } catch (reason) { error.value = reason instanceof Error ? reason.message : '加载失败' }
}

async function create() {
  saving.value = true; error.value = ''
  try {
    await api.createProject({
      projectName: form.projectName,
      sourceType: form.sourceType,
      localPath: form.sourceType === 'LOCAL' ? form.localPath : undefined,
      remoteUrl: form.sourceType === 'GIT' ? form.remoteUrl : undefined,
      defaultBranch: form.defaultBranch,
    })
    Object.assign(form, { projectName: '', localPath: '', remoteUrl: '', defaultBranch: 'main' })
    showForm.value = false
    await load()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '创建失败' } finally { saving.value = false }
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
      <div><h2>仓库资产</h2><p>登记已有本地仓库，或让 CodeSentry 维护一个远程 Git 镜像。</p></div>
      <button class="button primary" @click="showForm = !showForm">{{ showForm ? '收起表单' : '+ 登记仓库' }}</button>
    </section>

    <div v-if="error" class="error-banner">{{ error }}</div>

    <section v-if="showForm" class="panel panel-pad">
      <form class="form-grid" @submit.prevent="create">
        <div class="field span-2">
          <label>仓库来源</label>
          <div class="segmented">
            <button type="button" :class="{ active: form.sourceType === 'LOCAL' }" @click="form.sourceType = 'LOCAL'">已有本地目录</button>
            <button type="button" :class="{ active: form.sourceType === 'GIT' }" @click="form.sourceType = 'GIT'">远程 Git 地址</button>
          </div>
        </div>
        <div class="field"><label>项目名称</label><input v-model.trim="form.projectName" class="input" required maxlength="128" placeholder="例如：订单中心" /></div>
        <div class="field"><label>默认基线分支</label><input v-model.trim="form.defaultBranch" class="input" placeholder="main" /></div>
        <div v-if="form.sourceType === 'LOCAL'" class="field span-2">
          <label>本地 Git 仓库绝对路径</label><input v-model.trim="form.localPath" class="input mono" required placeholder="/Users/neo/code/project-a" />
          <small>CodeSentry 不会切换分支或执行 git pull；当前目录模式可以审查未提交代码。</small>
        </div>
        <div v-else class="field span-2">
          <label>Git 仓库地址</label><input v-model.trim="form.remoteUrl" class="input mono" required placeholder="git@gitlab.example.com:group/project.git" />
          <small>优先使用 SSH 和本机 ssh-agent。产品不保存私钥，也不调用 GitLab API。</small>
        </div>
        <div class="field span-2"><div class="notice">远程仓库登记时会执行一次 mirror clone，耗时取决于仓库大小。扫描时只按需导出指定 Commit，不创建 worktree。</div></div>
        <div class="form-actions field span-2"><button type="button" class="button secondary" @click="showForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? '正在准备仓库…' : '确认登记' }}</button></div>
      </form>
    </section>

    <section class="panel">
      <div v-if="projects.length" class="table-scroll">
        <table class="data-table">
          <thead><tr><th>ID / 项目</th><th>来源</th><th>仓库位置</th><th>默认分支</th><th>登记时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="project in projects" :key="project.id">
              <td><span class="mono">P-{{ String(project.id).padStart(3, '0') }}</span><div style="margin-top:5px"><strong>{{ project.projectName }}</strong></div></td>
              <td><span class="status-badge"><i></i>{{ project.sourceType === 'LOCAL' ? '本地目录' : '远程镜像' }}</span></td>
              <td class="mono">{{ project.localPath ?? project.remoteUrl }}</td>
              <td class="mono">{{ project.defaultBranch ?? '-' }}</td>
              <td class="mono">{{ new Date(project.createdAt).toLocaleDateString('zh-CN') }}</td>
              <td><button class="button danger compact" @click="remove(project)">移除</button></td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else title="还没有代码仓库" description="登记一个已经拉取到本机的 Git 目录，或者输入远程 Git 地址建立只读镜像。" />
    </section>
  </div>
</template>
