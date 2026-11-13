<script setup lang="ts">
import FindingRuleSettings from '@/components/FindingRuleSettings.vue'
import GitCredentialSettings from '@/components/GitCredentialSettings.vue'
import { currentTheme, setTheme, themes } from '@/utils/theme'
const tools = [
  { name: 'Semgrep CE', runtime: 'Python / 命令行', purpose: '静态代码分析与自定义规则', env: 'CODESENTRY_SEMGREP_EXECUTABLE' },
  { name: 'Gitleaks', runtime: 'Go 可执行程序', purpose: '敏感信息检测', env: 'CODESENTRY_GITLEAKS_EXECUTABLE' },
  { name: 'Trivy', runtime: 'Go 可执行程序', purpose: '依赖、基础设施配置与密钥检测', env: 'CODESENTRY_TRIVY_EXECUTABLE' },
  { name: 'SpotBugs / FindSecBugs', runtime: 'JVM', purpose: '预编译 JAR 字节码安全分析', env: 'CODESENTRY_SPOTBUGS_EXECUTABLE' },
]
</script>

<template>
  <div class="page-stack">
    <GitCredentialSettings />
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
    <FindingRuleSettings />
    <section class="panel panel-pad ai-config-panel">
      <div class="section-heading"><div><span class="eyebrow">工具辅助复核</span><h3>模型工具统一管理</h3><p>模型服务、默认模型与加密密钥现在统一在工具管理页面维护，支持多条配置的新增、编辑、切换和删除。</p></div><RouterLink to="/tools" class="button primary">进入工具管理</RouterLink></div>
      <div class="notice">工具配置写入本地数据库，API Key 使用本机持久化主密钥加密；容器和服务重启后会自动恢复，无需重复填写。</div>
    </section>
  </div>
</template>
