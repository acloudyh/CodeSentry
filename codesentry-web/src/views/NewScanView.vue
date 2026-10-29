<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api/codesentry'
import type { BranchView, RuleProfile, ScanMode, ScanProject, WorkspaceMode } from '@/types'

const router = useRouter()
const route = useRoute()
const projects = ref<ScanProject[]>([])
const branches = ref<BranchView[]>([])
const loadingBranches = ref(false)
const submitting = ref(false)
const error = ref('')
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

watch(() => form.projectId, async (id) => {
  if (!id) return
  if (!canCurrentWorkspace.value && form.workspaceMode === 'CURRENT') form.workspaceMode = 'BRANCH_SNAPSHOT'
  loadingBranches.value = true; error.value = ''
  try {
    branches.value = await api.branches(id, false)
    const defaultBranch = selectedProject.value?.defaultBranch ?? 'main'
    form.baselineRef = branches.value.some((item) => item.name === defaultBranch) ? defaultBranch : branches.value[0]?.name ?? ''
    form.scanRef = branches.value[0]?.name ?? ''
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '分支读取失败' } finally { loadingBranches.value = false }
})

async function submit() {
  submitting.value = true; error.value = ''
  try {
    await api.startScan({ ...form, scanRef: form.workspaceMode === 'CURRENT' ? undefined : form.scanRef,
      baselineRef: form.scanMode === 'FULL' ? undefined : form.baselineRef })
    await router.push('/scans')
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '扫描启动失败' } finally { submitting.value = false }
}

onMounted(async () => {
  try {
    [projects.value, ruleProfiles.value] = await Promise.all([api.projects(), api.ruleProfiles()])
    const requestedProfile = String(route.query.ruleProfile ?? '')
    if (ruleProfiles.value.some((item) => item.id === requestedProfile)) form.ruleProfile = requestedProfile
    form.projectId = projects.value[0]?.id ?? 0
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '项目或规则集加载失败' }
})
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>扫描任务配置</h2><p>选择代码来源、扫描版本和基线分支。增量模式会自动计算共同祖先（merge-base）。</p></div><span class="section-code">扫描配置</span></section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <form class="panel panel-pad" @submit.prevent="submit">
      <div class="form-grid">
        <div class="field span-2"><label>目标项目</label><select v-model.number="form.projectId" class="select" required><option :value="0" disabled>请选择仓库</option><option v-for="project in projects" :key="project.id" :value="project.id">{{ project.projectName }} · {{ project.sourceType === 'LOCAL' ? '本地' : '远程' }}</option></select></div>
        <div class="field"><label>扫描模式</label><div class="segmented"><button type="button" :class="{active:form.scanMode==='FULL'}" @click="form.scanMode='FULL'">全量扫描</button><button type="button" :class="{active:form.scanMode==='INCREMENTAL'}" @click="form.scanMode='INCREMENTAL'">增量扫描</button></div></div>
        <div class="field"><label>代码读取方式</label><div class="segmented"><button type="button" :disabled="!canCurrentWorkspace" :class="{active:form.workspaceMode==='CURRENT'}" @click="form.workspaceMode='CURRENT'">当前工作区</button><button type="button" :class="{active:form.workspaceMode==='BRANCH_SNAPSHOT'}" @click="form.workspaceMode='BRANCH_SNAPSHOT'">分支快照</button></div></div>
        <div v-if="form.workspaceMode === 'BRANCH_SNAPSHOT'" class="field"><label>待扫描分支</label><select v-model="form.scanRef" class="select" required><option v-for="branch in branches" :key="branch.name" :value="branch.name">{{ branch.name }} · {{ branch.commit.slice(0,8) }}</option></select><small>{{ loadingBranches ? '正在读取分支…' : '通过 git archive 导出，不切换原仓库分支。' }}</small></div>
        <div v-if="form.scanMode === 'INCREMENTAL'" class="field"><label>基线分支</label><select v-model="form.baselineRef" class="select" required><option v-for="branch in branches" :key="branch.name" :value="branch.name">{{ branch.name }} · {{ branch.commit.slice(0,8) }}</option></select><small>实际对比起点为基线分支与扫描版本的共同祖先。</small></div>
        <div class="field span-2"><label for="rule-profile">扫描规则集</label><select id="rule-profile" v-model="form.ruleProfile" class="select" required><option v-for="profile in ruleProfiles" :key="profile.id" :value="profile.id">{{ profile.name }}{{ profile.offlineReady ? ' · 支持离线' : '' }}</option></select><small>{{ ruleProfiles.find((item) => item.id === form.ruleProfile)?.description }}</small></div>
        <div v-if="selectedProject?.sourceType === 'GIT'" class="field span-2"><label style="display:flex;gap:10px;align-items:center"><input v-model="form.refreshRemote" type="checkbox" /> 扫描前同步远程分支（git remote update --prune）</label></div>
        <div class="field span-2"><div class="notice">精确增量会分别扫描当前版本和共同祖先快照（merge-base），通过稳定指纹识别新增、存量和已修复问题。第一版优先准确性，后续再增加快速增量缓存。</div></div>
        <div class="form-actions field span-2"><RouterLink to="/" class="button secondary">返回</RouterLink><button class="button primary" :disabled="submitting || !form.projectId">{{ submitting ? '正在创建任务…' : '启动扫描' }}</button></div>
      </div>
    </form>
  </div>
</template>
