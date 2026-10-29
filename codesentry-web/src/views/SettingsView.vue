<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/codesentry'
import { currentTheme, setTheme, themes } from '@/utils/theme'
import type { AiReviewConfiguration } from '@/types'

const ai = ref<AiReviewConfiguration>({ enabled: false, provider: 'OPENAI_COMPATIBLE' })
const aiError = ref('')
const tools = [
  { name: 'Semgrep CE', runtime: 'Python / 命令行', purpose: '静态代码分析与自定义规则', env: 'CODESENTRY_SEMGREP_EXECUTABLE' },
  { name: 'Gitleaks', runtime: 'Go 可执行程序', purpose: '敏感信息检测', env: 'CODESENTRY_GITLEAKS_EXECUTABLE' },
  { name: 'Trivy', runtime: 'Go 可执行程序', purpose: '依赖、基础设施配置与密钥检测', env: 'CODESENTRY_TRIVY_EXECUTABLE' },
  { name: 'Java 分析工具', runtime: 'JVM', purpose: 'PMD / SpotBugs / FindSecBugs', env: '下一阶段适配' },
]
onMounted(async () => {
  try { ai.value = await api.aiReviewConfig() }
  catch (reason) { aiError.value = reason instanceof Error ? reason.message : '工具配置读取失败' }
})
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
      <article class="panel panel-pad"><span class="eyebrow">部署模式</span><h3 style="font-size:24px;margin:16px 0 8px">单机本地模式</h3><p class="muted" style="line-height:1.8;font-size:12px">Java 服务、Vue 静态资源、MySQL 和扫描器全部运行在用户控制的机器中。未启用工具时不会向外发送源码；启用工具后会向配置的工具接口发送限长的真实源码片段，包含命中的凭据。</p><div class="notice" style="margin-top:22px">正式运行前应将 Semgrep 规则与 Trivy 漏洞库同步到本地，避免受公网可用性影响。</div></article>
    </section>
    <section class="panel panel-pad"><span class="eyebrow">工具辅助复核</span><h3 style="font-size:22px;margin:14px 0 8px">工具复核</h3><p class="muted">状态：{{ ai.enabled ? '已启用' : '未启用' }}</p><p class="muted">审核使用真实源码片段，可能包含密钥等敏感信息；工具建议独立保存，不会自动覆盖人工复核。</p><div v-if="aiError" class="error-banner">{{ aiError }}</div><div class="notice" style="margin-top:16px">工具服务由后端配置启用；前端仅展示运行状态。使用外部工具时，必要源码证据会提交至所配置的服务接口。</div></section>
  </div>
</template>
