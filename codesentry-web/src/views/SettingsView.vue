<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '@/api/codesentry'
import { currentTheme, setTheme, themes } from '@/utils/theme'
import type { AiReviewConfiguration } from '@/types'

const ai = ref<AiReviewConfiguration>({ enabled: false, provider: 'OPENAI_COMPATIBLE', apiKeyConfigured: false })
const aiError = ref('')
const aiSaved = ref('')
const aiSaving = ref(false)
const aiForm = reactive({ enabled: false, baseUrl: '', model: '', apiKey: '' })
const tools = [
  { name: 'Semgrep CE', runtime: 'Python / 命令行', purpose: '静态代码分析与自定义规则', env: 'CODESENTRY_SEMGREP_EXECUTABLE' },
  { name: 'Gitleaks', runtime: 'Go 可执行程序', purpose: '敏感信息检测', env: 'CODESENTRY_GITLEAKS_EXECUTABLE' },
  { name: 'Trivy', runtime: 'Go 可执行程序', purpose: '依赖、基础设施配置与密钥检测', env: 'CODESENTRY_TRIVY_EXECUTABLE' },
  { name: 'SpotBugs / FindSecBugs', runtime: 'JVM', purpose: '预编译 JAR 字节码安全分析', env: 'CODESENTRY_SPOTBUGS_EXECUTABLE' },
]
onMounted(async () => {
  try {
    ai.value = await api.aiReviewConfig()
    aiForm.enabled = ai.value.enabled
    aiForm.baseUrl = ai.value.baseUrl ?? ''
    aiForm.model = ai.value.model ?? ''
  }
  catch (reason) { aiError.value = reason instanceof Error ? reason.message : '工具配置读取失败' }
})

async function saveAiConfiguration() {
  aiSaving.value = true; aiError.value = ''; aiSaved.value = ''
  try {
    ai.value = await api.updateAiReviewConfig({ ...aiForm, apiKey: aiForm.apiKey || undefined })
    aiForm.enabled = ai.value.enabled
    aiForm.apiKey = ''
    aiSaved.value = ai.value.enabled ? '工具复核已启用' : '工具复核已关闭'
  } catch (reason) { aiError.value = reason instanceof Error ? reason.message : '工具复核配置保存失败' }
  finally { aiSaving.value = false }
}
</script>

<template>
  <div class="page-stack">
    <section class="panel panel-pad theme-settings">
      <div class="section-heading"><div><h2>页面主题</h2><p>选择喜欢的配色，立即应用到全部页面。默认经典蓝，主题仅保存在当前浏览器。</p></div><span class="section-code">{{ themes.length }} 套配色方案</span></div>
      <div class="theme-options">
        <button v-for="theme in themes" :key="theme.id" type="button" class="theme-option" :class="{ selected: currentTheme === theme.id }" :aria-pressed="currentTheme === theme.id" :aria-label="`选择${theme.name}主题`" @click="setTheme(theme.id)">
          <span class="theme-preview" :style="{ '--preview-bg': theme.background, '--preview-surface': theme.surface, '--preview-accent': theme.accent, '--preview-rail': theme.rail, '--preview-rail-line': theme.id === 'blue' ? '#aebce6' : '#d3d7dc', '--preview-active': theme.id === 'blue' ? '#e8ebf5' : theme.accent }" aria-hidden="true"><span class="preview-rail"><i></i><b></b><i></i><i></i></span><span class="preview-content"><span class="preview-title"></span><span class="preview-metrics"><i></i><i></i><i></i></span><span class="preview-table"><i></i><i></i><i></i></span></span></span>
          <span class="theme-option-copy"><strong>{{ theme.name }}</strong><span class="theme-chosen">{{ currentTheme === theme.id ? '正在使用' : '点击预览' }}</span><small>{{ theme.description }}</small></span>
        </button>
      </div>
    </section>
    <section class="section-heading"><div><h2>运行环境</h2><p>敏感配置通过环境变量传入，不在前端页面保存数据库密码或 Git 私钥。</p></div><span class="section-code">本地运行配置</span></section>
    <section class="split-grid">
      <article class="panel"><header class="panel-header"><h3>扫描器适配</h3><small>命令行工具</small></header><div class="table-scroll"><table class="data-table"><thead><tr><th>工具</th><th>运行时</th><th>职责</th><th>配置</th></tr></thead><tbody><tr v-for="tool in tools" :key="tool.name"><td><strong>{{ tool.name }}</strong></td><td class="mono">{{ tool.runtime }}</td><td>{{ tool.purpose }}</td><td class="mono">{{ tool.env }}</td></tr></tbody></table></div></article>
      <article class="panel panel-pad"><span class="eyebrow">部署模式</span><h3 style="font-size:24px;margin:16px 0 8px">单机本地模式</h3><p class="muted" style="line-height:1.8;font-size:12px">Java 服务、Vue 静态资源、MySQL 和扫描器全部运行在用户控制的机器中。未启用工具时不会向外发送源码；启用工具后仅向配置的工具接口发送限长且已执行凭据脱敏的源码证据。</p><div class="notice" style="margin-top:22px">正式运行前应将 Semgrep 规则与 Trivy 漏洞库同步到本地，避免受公网可用性影响。</div></article>
    </section>
    <section class="panel panel-pad ai-config-panel">
      <div class="section-heading"><div><span class="eyebrow">工具辅助复核</span><h3>双轮工具复核</h3><p>配置 OpenAI 兼容服务后，可在问题台账执行批量初审与复审。人工结论始终优先。</p></div><span :class="['config-state', { enabled: ai.enabled }]">{{ ai.enabled ? '已启用' : '未启用' }}</span></div>
      <form class="form-grid ai-config-grid" @submit.prevent="saveAiConfiguration">
        <label class="switch-field"><input v-model="aiForm.enabled" type="checkbox" /><span><b>启用工具复核</b><small>启用后，问题台账显示批量复核入口。</small></span></label>
        <div class="field"><label for="ai-base-url">服务地址</label><input id="ai-base-url" v-model.trim="aiForm.baseUrl" class="input" placeholder="https://example.com/v1" autocomplete="url" /></div>
        <div class="field"><label for="ai-model">模型名称</label><input id="ai-model" v-model.trim="aiForm.model" class="input" placeholder="模型 ID" autocomplete="off" /></div>
        <div class="field"><label for="ai-key">API Key</label><input id="ai-key" v-model="aiForm.apiKey" class="input" type="password" :placeholder="ai.apiKeyConfigured ? '已配置；留空表示保持不变' : '请输入 API Key'" autocomplete="new-password" /><small>密钥不会回显，也不会写入前端存储。</small></div>
        <div class="form-actions span-2"><span v-if="aiSaved" class="success-text">{{ aiSaved }}</span><button class="button primary" :disabled="aiSaving">{{ aiSaving ? '正在保存…' : '保存工具复核配置' }}</button></div>
      </form>
      <div v-if="aiError" class="error-banner">{{ aiError }}</div>
      <div class="notice">配置仅保存在当前后端进程内，服务重启后需重新填写。提交给外部工具的源码证据会先进行密钥、令牌和口令脱敏；问题台账本机页面仍可查看原始文本。</div>
    </section>
  </div>
</template>
