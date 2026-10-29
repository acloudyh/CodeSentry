<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '@/api/codesentry'
import { scannerLabel, standardLabel } from '@/utils/labels'
import EmptyState from '@/components/ui/EmptyState.vue'
import type { RuleProfile } from '@/types'

const profiles = ref<RuleProfile[]>([])
const error = ref('')
onMounted(async () => {
  try { profiles.value = await api.ruleProfiles() }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '扫描规则加载失败' }
})
</script>

<template>
  <div class="page-stack">
    <section class="section-heading">
      <div><h2>扫描规则中心</h2><p>规则不限定单一厂商，按 OWASP、CWE、CERT 和社区通用规范组织；发起扫描时可选择规则集。</p></div>
      <RouterLink to="/scans/new" class="button primary">使用规则发起扫描</RouterLink>
    </section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <section v-if="profiles.length" class="rule-grid">
      <article v-for="(profile, index) in profiles" :key="profile.id" class="rule-card">
        <header><span class="rule-index">0{{ index + 1 }}</span><span :class="['rule-source', { offline: profile.offlineReady }]">{{ profile.offlineReady ? '本地可用' : '需要网络' }}</span></header>
        <h3>{{ profile.name }}</h3><p>{{ profile.description }}</p>
        <div class="rule-block"><b>标准映射</b><div class="tag-row"><span v-for="item in profile.standards" :key="item">{{ standardLabel(item) }}</span></div></div>
        <div class="rule-block"><b>扫描引擎</b><div class="tag-row"><span v-for="item in profile.scanners" :key="item">{{ scannerLabel(item) }}</span></div></div>
        <footer><code>{{ profile.id }}</code><RouterLink :to="{ path: '/scans/new', query: { ruleProfile: profile.id } }">选择此规则集 →</RouterLink></footer>
      </article>
    </section>
    <EmptyState v-else-if="!error" title="暂无可用规则集" description="请检查本地服务状态。" />
    <section class="notice">内置规则用于建立可离线执行的安全基线；社区规则能够扩大语言覆盖，但规则首次拉取受本机网络影响。Gitleaks 与 Trivy 密钥扫描会继续承担凭据泄露检测。</section>
  </div>
</template>
