<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api } from '@/api/codesentry'

const route = useRoute()
const html = ref(''),
  loading = ref(false),
  error = ref('')
const source = computed(() =>
  route.params.roundId
    ? api.projectReportUrl(Number(route.params.id), Number(route.params.roundId), 'html')
    : api.reportUrl(Number(route.params.id), 'html'),
)
let request: AbortController | undefined
let generation = 0
/** 先展示加载状态，再请求报告；取消旧请求并显式提供失败重试，避免新标签页长时间空白。 */
async function load() {
  request?.abort()
  const controller = new AbortController(),
    token = ++generation
  request = controller
  loading.value = true
  error.value = ''
  html.value = ''
  const timeout = setTimeout(() => controller.abort(), 90000)
  try {
    if (route.params.roundId)
      await api.archiveProjectReport(
        Number(route.params.id),
        Number(route.params.roundId),
        controller.signal,
      )
    else await api.archiveReport(Number(route.params.id), controller.signal)
    const response = await fetch(source.value, { signal: controller.signal, cache: 'no-store' })
    if (!response.ok) {
      const body = await response.json().catch(() => null)
      throw new Error(body?.message ?? `报告加载失败（${response.status}）`)
    }
    if (!response.headers.get('content-type')?.includes('text/html'))
      throw new Error('报告响应格式异常，请重试')
    const content = await response.text()
    if (!content.trim()) throw new Error('报告内容为空，请重试')
    if (token === generation) html.value = content
  } catch (reason) {
    if (token === generation)
      error.value = controller.signal.aborted ? '报告加载超时，请重试。' : (reason as Error).message
  } finally {
    clearTimeout(timeout)
    if (token === generation) loading.value = false
  }
}
watch(source, load, { immediate: true })
onBeforeUnmount(() => {
  generation++
  request?.abort()
})
</script>

<template>
  <section class="page-stack report-viewer">
    <div class="section-heading">
      <div>
        <h2>整改报告预览</h2>
        <p>报告在隔离区域内展示，保留原问题编号和来源扫描。</p>
      </div>
      <button class="button secondary" :disabled="loading" @click="load">重新加载</button>
    </div>
    <div v-if="loading" class="notice" role="status">正在生成并加载整改报告，请稍候…</div>
    <div v-else-if="error" class="error-banner" role="alert">
      {{ error }} <button class="button secondary compact" @click="load">重试</button>
    </div>
    <!-- 不启用脚本、同源访问或弹窗权限，源码即使含 HTML 也不能影响产品页面。 -->
    <iframe
      v-else-if="html"
      title="整改报告内容"
      sandbox=""
      referrerpolicy="no-referrer"
      :srcdoc="html"
    />
  </section>
</template>

<style scoped>
.report-viewer iframe {
  width: 100%;
  height: calc(100vh - 245px);
  min-height: 600px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: #fff;
}
</style>
