<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/codesentry'
import type { AiReviewConfiguration } from '@/types'

const ai = ref<AiReviewConfiguration>({ enabled: false, provider: 'OPENAI_COMPATIBLE' })
const aiError = ref('')
const tools = [
  { name: 'Semgrep CE', runtime: 'Python / CLI', purpose: 'SAST 与自定义规则', env: 'CODESENTRY_SEMGREP_EXECUTABLE' },
  { name: 'Gitleaks', runtime: 'Go Binary', purpose: '敏感信息检测', env: 'CODESENTRY_GITLEAKS_EXECUTABLE' },
  { name: 'Trivy', runtime: 'Go Binary', purpose: '依赖、IaC 与密钥', env: 'CODESENTRY_TRIVY_EXECUTABLE' },
  { name: 'Java Analyzers', runtime: 'JVM', purpose: 'PMD / SpotBugs / FindSecBugs', env: '下一阶段适配' },
]
onMounted(async () => {
  try { ai.value = await api.aiReviewConfig() }
  catch (reason) { aiError.value = reason instanceof Error ? reason.message : 'AI 配置读取失败' }
})
</script>

<template>
  <div class="page-stack">
    <section class="section-heading"><div><h2>运行环境</h2><p>敏感配置通过环境变量传入，不在前端页面保存数据库密码或 Git 私钥。</p></div><span class="section-code">LOCAL NODE / CONFIG</span></section>
    <section class="split-grid">
      <article class="panel"><header class="panel-header"><h3>扫描器适配</h3><small>CLI ADAPTERS</small></header><div class="table-scroll"><table class="data-table"><thead><tr><th>工具</th><th>运行时</th><th>职责</th><th>配置</th></tr></thead><tbody><tr v-for="tool in tools" :key="tool.name"><td><strong>{{ tool.name }}</strong></td><td class="mono">{{ tool.runtime }}</td><td>{{ tool.purpose }}</td><td class="mono">{{ tool.env }}</td></tr></tbody></table></div></article>
      <article class="panel panel-pad"><span class="eyebrow">DEPLOYMENT PROFILE</span><h3 style="font-size:24px;margin:16px 0 8px">单机本地模式</h3><p class="muted" style="line-height:1.8;font-size:12px">Java 服务、Vue 静态资源、MySQL 和扫描器全部运行在用户控制的机器中。未启用 AI 时不会向外发送源码；启用 AI 后也只发送脱敏、限长的最小审核证据。</p><div class="notice" style="margin-top:22px">正式运行前应将 Semgrep 规则与 Trivy 漏洞库同步到本地，避免受公网可用性影响。</div></article>
    </section>
    <section class="panel panel-pad"><span class="eyebrow">AI ASSISTED REVIEW</span><h3 style="font-size:22px;margin:14px 0 8px">AI 双轮辅助复核</h3><p class="muted">状态：{{ ai.enabled ? '已启用' : '未启用' }}<template v-if="ai.enabled"> · {{ ai.provider }} / {{ ai.model }}</template></p><p class="muted">只发送本地脱敏后的最小代码证据；AI 建议独立保存，不会自动覆盖人工复核或删除问题。</p><div v-if="aiError" class="error-banner">{{ aiError }}</div><div class="notice" style="margin-top:16px">通过 CODESENTRY_AI_ENABLED、CODESENTRY_AI_BASE_URL、CODESENTRY_AI_MODEL 和 CODESENTRY_AI_API_KEY 环境变量配置，前端不会读取或展示 API Key。</div></section>
  </div>
</template>
