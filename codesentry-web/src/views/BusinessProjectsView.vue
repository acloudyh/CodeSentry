<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api/codesentry'
import type { BusinessProject, ScanProject } from '@/types'
import AppModal from '@/components/ui/AppModal.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
const projects = ref<BusinessProject[]>([])
const assets = ref<ScanProject[]>([])
const unassigned = computed(() => assets.value.filter(a => !a.businessProjectId))
const error = ref(''), busy = ref(false), open = ref(false)
const editing = ref<number | null>(null), name = ref(''), description = ref('')
async function load() { try { [projects.value, assets.value] = await Promise.all([api.businessProjects(), api.projects()]) } catch(e) { error.value = (e as Error).message } }
function edit(project?: BusinessProject) { editing.value = project?.id ?? null; name.value = project?.name ?? ''; description.value = project?.description ?? ''; error.value = ''; open.value = true }
async function save() { busy.value = true; try { await api.saveBusinessProject(editing.value, { name: name.value.trim(), description: description.value.trim() }); open.value = false; await load() } catch(e) { error.value = (e as Error).message } finally { busy.value = false } }
async function remove(p: BusinessProject) { if(!window.confirm(`删除业务项目“${p.name}”？仅无资产且无扫描历史的项目可删除。`)) return; try { await api.deleteBusinessProject(p.id); await load() } catch(e) { error.value = (e as Error).message } }
onMounted(load)
</script>
<template>
  <div class="page-stack">
    <section class="section-heading"><div><span class="eyebrow">PROJECT / SECURITY OVERVIEW</span><h2>项目概览</h2><p>以业务项目组织代码仓库与 JAR，统一扫描、复核和整改交付。</p></div><button class="button primary" @click="edit()">＋ 新建项目</button></section>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <section class="panel" v-if="projects.length"><div class="table-scroll"><table class="data-table business-projects-table"><thead><tr><th>业务项目</th><th>备注</th><th>代码 / JAR</th><th>最近一轮扫描</th><th>扫描覆盖</th><th>操作</th></tr></thead><tbody><tr v-for="p in projects" :key="p.id"><td class="project-name-cell"><RouterLink :to="`/business-projects/${p.id}`"><strong>{{ p.name }}</strong></RouterLink></td><td><span class="project-remark muted" :title="p.description || undefined">{{ p.description || '—' }}</span></td><td class="mono">{{ p.repositoryCount }} / {{ p.jarCount }}</td><td><template v-if="p.latestRound"><StatusBadge :value="p.latestRound.status" /><small v-if="p.latestRound.assetHistory" class="muted">资产历史扫描 #{{ -p.latestRound.id }}</small></template><span v-else class="muted">尚未扫描</span></td><td>{{ p.latestRound ? `${p.latestRound.assetHistory ? '资产历史' : p.latestRound.fullScope ? '全部' : '部分'} · ${p.latestRound.items.length}/${p.latestRound.assetCount} 个资产` : '—' }}</td><td><div class="row-actions"><RouterLink class="button primary compact" :to="`/business-projects/${p.id}`">查看项目</RouterLink><RouterLink class="row-action-link" :to="`/business-projects/${p.id}?scan=all`">扫描</RouterLink><button class="row-action-link" @click="edit(p)">编辑</button><button class="row-action-link danger" @click="remove(p)">删除</button></div></td></tr></tbody></table></div></section>
    <EmptyState v-else title="建立第一个业务项目" description="将已有仓库和 JAR 关联到项目，也可以在项目内登记新资产。" />
    <section class="panel unassigned"><div><h3>未归属资产</h3><p class="muted">代码 {{ unassigned.filter(a => a.sourceType !== 'JAR_UPLOAD').length }} / JAR {{ unassigned.filter(a => a.sourceType === 'JAR_UPLOAD').length }} · 保留原有独立扫描与历史记录</p></div><RouterLink class="button secondary" to="/projects?unassigned=1">管理资产</RouterLink></section>
    <AppModal :open="open" :title="editing ? '编辑业务项目' : '新建业务项目'" :busy="busy" @close="open = false"><form class="form-grid" @submit.prevent="save"><div class="field span-2"><label for="business-name">项目名称</label><input id="business-name" v-model="name" class="input" required maxlength="128" :disabled="busy" /></div><div class="field span-2"><label for="business-description">项目描述</label><textarea id="business-description" v-model="description" class="input" rows="3" maxlength="2000" :disabled="busy" /></div><div class="error-banner span-2" v-if="error">{{ error }}</div><div class="form-actions span-2"><button class="button primary" :disabled="busy">{{ busy ? '保存中…' : '保存项目' }}</button></div></form></AppModal>
  </div>
</template>
<style scoped>.business-projects-table{min-width:1080px}.project-name-cell{white-space:nowrap}.project-remark{display:block;max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.unassigned{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:24px}.unassigned p{margin-top:8px}</style>
