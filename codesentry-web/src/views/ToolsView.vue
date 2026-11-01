<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { api, type AiToolConfigurationPayload } from '@/api/codesentry'
import { formatChinaTime } from '@/utils/datetime'
import type { AiToolConfiguration } from '@/types'

const providers = [
  {
    id: 'DEEPSEEK', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com',
    models: ['deepseek-chat', 'deepseek-reasoner', 'deepseek-v4pro', 'deepseek-flash'],
    note: '使用 DeepSeek OpenAI 兼容接口，服务地址由系统自动填写。',
  },
  {
    id: 'OPENAI_COMPATIBLE', label: 'OpenAI 兼容服务', baseUrl: '', models: [],
    note: '适用于其他兼容 /chat/completions 的模型平台，地址与模型 ID 由你填写。',
  },
]

const tools = ref<AiToolConfiguration[]>([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const message = ref('')
const dialogOpen = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<AiToolConfigurationPayload>({
  name: '', provider: 'DEEPSEEK', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat',
  apiKey: '', enabled: true, defaultTool: false,
})

const selectedProvider = computed(() => providers.find((item) => item.id === form.provider) ?? providers[1])
const isCustom = computed(() => form.provider === 'OPENAI_COMPATIBLE')

async function load() {
  loading.value = true; error.value = ''
  try { tools.value = await api.aiTools() }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '工具列表读取失败' }
  finally { loading.value = false }
}

function applyProvider() {
  const provider = selectedProvider.value
  if (provider.baseUrl) form.baseUrl = provider.baseUrl
  if (provider.models.length && !provider.models.includes(form.model)) form.model = provider.models[0]
  if (!form.name || providers.some((item) => `${item.label} 复核工具` === form.name)) form.name = `${provider.label} 复核工具`
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: 'DeepSeek 复核工具', provider: 'DEEPSEEK', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', apiKey: '', enabled: true, defaultTool: !tools.value.length })
  error.value = ''; message.value = ''; dialogOpen.value = true
}

function openEdit(tool: AiToolConfiguration) {
  editingId.value = tool.id
  Object.assign(form, { name: tool.name, provider: tool.provider, baseUrl: tool.baseUrl, model: tool.model, apiKey: '', enabled: tool.enabled, defaultTool: tool.defaultTool })
  error.value = ''; message.value = ''; dialogOpen.value = true
}

async function save() {
  saving.value = true; error.value = ''; message.value = ''
  try {
    const payload = { ...form, apiKey: form.apiKey || undefined }
    if (editingId.value) await api.updateAiTool(editingId.value, payload)
    else await api.createAiTool(payload)
    message.value = editingId.value ? '工具配置已更新并写入数据库' : '工具已新增并写入数据库'
    await load(); dialogOpen.value = false
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '工具配置保存失败' }
  finally { saving.value = false }
}

async function makeDefault(tool: AiToolConfiguration) {
  error.value = ''; message.value = ''
  try {
    await api.updateAiTool(tool.id, { name: tool.name, provider: tool.provider, baseUrl: tool.baseUrl, model: tool.model, enabled: true, defaultTool: true })
    message.value = `${tool.name} 已设为当前工具`; await load()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '默认工具切换失败' }
}

async function removeTool(tool: AiToolConfiguration) {
  if (!window.confirm(`确定删除“${tool.name}”吗？该操作不会删除历史复核记录。`)) return
  error.value = ''; message.value = ''
  try { await api.deleteAiTool(tool.id); message.value = '工具配置已删除'; await load() }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '工具删除失败' }
}

onMounted(load)
</script>

<template>
  <div class="page-stack tools-page">
    <section class="section-heading tools-heading">
      <div><span class="eyebrow">模型接入中心</span><h2>工具管理</h2><p>统一维护工具复核所使用的模型服务。配置写入本地数据库，API Key 加密保存，服务或容器重启后自动恢复。</p></div>
      <button class="button primary" @click="openCreate">＋ 新增工具</button>
    </section>

    <div v-if="error" class="error-banner">{{ error }}</div>
    <div v-if="message" class="success-banner">{{ message }}</div>

    <section class="tool-overview">
      <article class="tool-metric"><span>已接入</span><strong>{{ tools.length }}</strong><small>模型工具</small></article>
      <article class="tool-metric"><span>已启用</span><strong>{{ tools.filter(item => item.enabled).length }}</strong><small>可参与复核</small></article>
      <article class="tool-metric active-tool"><span>当前工具</span><strong>{{ tools.find(item => item.defaultTool)?.name || '尚未设置' }}</strong><small>{{ tools.find(item => item.defaultTool)?.model || '新增工具后自动启用' }}</small></article>
    </section>

    <section class="panel tool-list-panel">
      <header class="panel-header"><div><h3>模型工具列表</h3><small>密钥只显示配置状态，不提供明文读取</small></div><span class="section-code">{{ tools.length }} 条记录</span></header>
      <div v-if="loading" class="empty-state compact-empty"><h3>正在读取工具配置</h3></div>
      <div v-else-if="tools.length" class="table-scroll">
        <table class="data-table tool-table">
          <thead><tr><th>工具名称</th><th>服务商 / 模型</th><th>服务地址</th><th>密钥</th><th>状态</th><th>更新时间</th><th>操作</th></tr></thead>
          <tbody><tr v-for="tool in tools" :key="tool.id">
            <td><div class="tool-name"><span class="provider-mark">{{ tool.provider === 'DEEPSEEK' ? 'DS' : 'AI' }}</span><div><strong>{{ tool.name }}</strong><small v-if="tool.defaultTool">当前默认工具</small></div></div></td>
            <td><strong>{{ providers.find(item => item.id === tool.provider)?.label || tool.provider }}</strong><div class="mono subtle">{{ tool.model }}</div></td>
            <td><span class="endpoint-text" :title="tool.baseUrl">{{ tool.baseUrl }}</span></td>
            <td><span :class="['key-state', { ready: tool.apiKeyConfigured }]">{{ tool.apiKeyConfigured ? '● 已配置' : '○ 未配置' }}</span></td>
            <td><span :class="['config-state', { enabled: tool.enabled }]">{{ tool.enabled ? '已启用' : '已停用' }}</span></td>
            <td class="mono subtle">{{ formatChinaTime(tool.updatedAt) }}</td>
            <td><div class="row-actions"><button v-if="!tool.defaultTool" class="text-button" @click="makeDefault(tool)">设为当前</button><button class="text-button" @click="openEdit(tool)">编辑</button><button class="text-button danger-text" @click="removeTool(tool)">删除</button></div></td>
          </tr></tbody>
        </table>
      </div>
      <div v-else class="empty-state"><h3>还没有模型工具</h3><p>新增 DeepSeek 或其他 OpenAI 兼容服务后，即可在问题台账执行双轮工具复核。</p><button class="button primary" @click="openCreate">新增第一个工具</button></div>
    </section>

    <div v-if="dialogOpen" class="modal-backdrop" @click.self="dialogOpen = false">
      <section class="review-dialog tool-dialog" role="dialog" aria-modal="true" :aria-label="editingId ? '编辑工具' : '新增工具'">
        <header class="review-head"><div><span class="eyebrow">{{ editingId ? '编辑模型接入' : '新增模型接入' }}</span><h2>{{ editingId ? '更新工具配置' : '创建工具配置' }}</h2><p>选择服务商和模型后自动补全推荐地址；API Key 由你输入并加密入库。</p></div><button class="icon-button" aria-label="关闭" @click="dialogOpen = false">×</button></header>
        <form class="form-grid tool-form" @submit.prevent="save">
          <div class="field"><label for="tool-name">工具名称</label><input id="tool-name" v-model.trim="form.name" class="input" maxlength="120" required /><small class="field-hint" aria-hidden="true">&nbsp;</small></div>
          <div class="field"><label for="tool-provider">服务商</label><select id="tool-provider" v-model="form.provider" class="select" @change="applyProvider"><option v-for="provider in providers" :key="provider.id" :value="provider.id">{{ provider.label }}</option></select><small>{{ selectedProvider.note }}</small></div>
          <div class="field"><label for="tool-model">模型</label><select v-if="!isCustom" id="tool-model" v-model="form.model" class="select" required><option v-for="model in selectedProvider.models" :key="model" :value="model">{{ model }}</option></select><input v-else id="tool-model" v-model.trim="form.model" class="input" placeholder="请输入模型 ID" required /><small class="field-hint">{{ isCustom ? '填写服务端实际使用的模型 ID。' : '选择该服务商支持的复核模型。' }}</small></div>
          <div class="field"><label for="tool-base-url">服务地址</label><input id="tool-base-url" v-model.trim="form.baseUrl" class="input" :readonly="!isCustom" placeholder="https://example.com/v1" required /><small>{{ isCustom ? '请输入兼容 OpenAI Chat Completions 的服务根地址。' : '已根据服务商自动填充。' }}</small></div>
          <div class="field span-2"><label for="tool-api-key">API Key</label><input id="tool-api-key" v-model="form.apiKey" class="input" type="password" :required="!editingId" :placeholder="editingId ? '已配置；留空保持原密钥' : '请输入 API Key'" autocomplete="new-password" /><small>密钥使用 AES-GCM 加密后写入本地数据库，不会回显到页面。</small></div>
          <label class="switch-field"><input v-model="form.enabled" type="checkbox" /><span><b>启用此工具</b><small>停用后保留配置，但不会用于新的工具复核。</small></span></label>
          <label class="switch-field"><input v-model="form.defaultTool" type="checkbox" /><span><b>设为当前工具</b><small>新的工具复核优先使用当前工具。</small></span></label>
          <div v-if="error" class="error-banner span-2">{{ error }}</div>
          <div class="form-actions span-2"><button type="button" class="button secondary" @click="dialogOpen = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? '正在保存…' : (editingId ? '保存修改' : '新增工具') }}</button></div>
        </form>
      </section>
    </div>
  </div>
</template>
