<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { api } from '@/api/codesentry'
import { scannerLabel, standardLabel } from '@/utils/labels'
import EmptyState from '@/components/ui/EmptyState.vue'
import type { RuleProfile, RuleLibraryStatus } from '@/types'

const profiles = ref<RuleProfile[]>([])
const library = ref<RuleLibraryStatus | null>(null)
const error = ref('')
const starting = ref(false)
const updating = computed(() => starting.value || library.value?.state === 'RUNNING')
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
function schedule() {
  clearTimeout(timer)
  if (!disposed && library.value?.state === 'RUNNING') timer = setTimeout(refresh, 1500)
}
async function refresh() {
  try {
    const [rules, status] = await Promise.all([api.ruleProfiles(), api.ruleLibraryStatus()])
    if (disposed) return
    profiles.value = rules; library.value = status; error.value = ''
  } catch (reason) { if (!disposed) error.value = reason instanceof Error ? reason.message : '扫描规则加载失败' }
  finally { schedule() }
}
async function update() {
  starting.value = true; error.value = ''
  try { library.value = await api.updateRuleLibrary(); schedule() }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '更新请求失败' }
  finally { starting.value = false }
}
onMounted(refresh)
onUnmounted(() => { disposed = true; clearTimeout(timer) })
</script>

<template>
  <div class="page-stack">
    <section class="section-heading">
      <div><h2>扫描规则中心</h2><p>规则不限定单一厂商，按 OWASP、CWE、CERT 和社区通用规范组织；发起扫描时可选择规则集。</p></div>
      <RouterLink to="/projects?scan=1" class="button primary">使用规则发起扫描</RouterLink>
    </section>
    <section class="panel library-panel" aria-label="社区规则库更新" :aria-busy="updating">
      <div class="library-info">
        <h3>本地社区规则库 <span class="rule-source" :class="{ offline: library?.available }">{{ library?.available ? '本地可用' : '尚未同步' }}</span></h3>
        <p>更新时联网下载并校验，扫描时使用本地快照；更新失败会保留原版本。</p>
        <p v-if="library?.available" class="library-meta">版本 <code>{{ library.version }}</code> · {{ library.ruleCount }} 条规则 · 最近更新 {{ library.updatedAt?.replace('T', ' ').slice(0, 19) }}（东八区）</p>
        <p v-if="library" class="library-message" :class="{ failed: library.state === 'FAILED' }" role="status">{{ library.message }}</p>
      </div>
      <button class="button secondary" :disabled="updating" @click="update">{{ updating ? '正在更新规则库…' : '手动更新规则库' }}</button>
    </section>
    <div v-if="error" class="error-banner">{{ error }}</div>
    <section v-if="profiles.length" class="rule-grid">
      <article v-for="(profile, index) in profiles" :key="profile.id" class="rule-card">
        <header><span class="rule-index">0{{ index + 1 }}</span><span :class="['rule-source', { offline: profile.offlineReady }]">{{ profile.offlineReady ? '本地可用' : '尚未同步' }}</span></header>
        <h3>{{ profile.name }}</h3><p>{{ profile.description }}</p>
        <div class="rule-block"><b>标准映射</b><div class="tag-row"><span v-for="item in profile.standards" :key="item">{{ standardLabel(item) }}</span></div></div>
        <div class="rule-block"><b>扫描引擎</b><div class="tag-row"><span v-for="item in profile.scanners" :key="item">{{ scannerLabel(item) }}</span></div></div>
        <div v-if="profile.rules?.length" class="rule-block"><b>本地专项规则（JSP / 前端）</b><div class="tag-row rule-detail-row"><span v-for="item in profile.rules" :key="item">{{ item }}</span></div></div>
        <footer><code>{{ profile.id }}</code><RouterLink :to="{ path: '/projects', query: { scan: '1', ruleProfile: profile.id } }">选择此规则集 →</RouterLink></footer>
      </article>
    </section>
    <EmptyState v-else-if="!error" title="暂无可用规则集" description="请检查本地服务状态。" />
    <section class="notice">本地与综合规则集包含前端专项规则，覆盖 JS、TS、JSX、TSX 和部分 Vue 模式；Vue 不提供完整跨文件分析。社区规则集不自动加载本地规则。压缩 JS 与部分第三方库默认排除，eval 与直接 innerHTML 规则仍不启用。Gitleaks 与 Trivy 继续提供密钥及依赖漏洞补充检查。</section>
  </div>
</template>

<style scoped>
.library-panel { display: flex; justify-content: space-between; align-items: center; gap: 24px; padding: 24px; }
.library-info { min-width: 0; }
.library-info h3 { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 0 0 10px; }
.library-info p { margin: 6px 0; color: var(--muted); line-height: 1.6; }
.library-info .library-meta { font-size: 13px; overflow-wrap: anywhere; }
.library-info .library-message { font-size: 13px; }
.library-info .failed { color: #b45309; }
.library-panel > button { flex-shrink: 0; }
@media (max-width: 760px) { .library-panel { align-items: stretch; flex-direction: column; } }
</style>
