<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { api } from '@/api/codesentry'
import type { AiReviewJob, ReviewPlan } from '@/types'
import AppModal from '@/components/ui/AppModal.vue'
const props = defineProps<{
  open: boolean
  runs: { runId: number; assetName: string }[]
  findingId?: number
  failedOnly?: boolean
}>()
const emit = defineEmits<{ close: []; started: [jobs: AiReviewJob[]] }>()
const plans = ref<ReviewPlan[]>([]),
  loading = ref(false),
  starting = ref(false),
  error = ref('')
const includeDependencies = ref(false)
let generation = 0
const totals = computed(() =>
  plans.value.reduce(
    (s, p) => ({
      total: s.total + p.total,
      quality: s.quality + p.quality,
      security: s.security + p.security,
      audit: s.audit + p.audit,
      local: s.local + p.localPrecheck,
      done: s.done + p.alreadyReviewed,
      reuse: s.reuse + p.reusable,
      candidates: s.candidates + p.modelCandidates,
      requests: s.requests + p.estimatedRequests,
    }),
    {
      total: 0,
      quality: 0,
      security: 0,
      audit: 0,
      local: 0,
      done: 0,
      reuse: 0,
      candidates: 0,
      requests: 0,
    },
  ),
)
const canStart = computed(
  () =>
    plans.value.length > 0 &&
    !loading.value &&
    (totals.value.candidates > 0 || totals.value.reuse > 0),
)
async function preview() {
  const token = ++generation
  loading.value = true
  error.value = ''
  plans.value = []
  try {
    const result = await Promise.all(
      props.runs.map((r) =>
        api.previewReview(r.runId, {
          findingId: props.findingId,
          failedOnly: props.failedOnly,
          includeDependencies: includeDependencies.value,
        }),
      ),
    )
    if (token === generation) plans.value = result
  } catch (e) {
    if (token === generation) error.value = (e as Error).message
  } finally {
    if (token === generation) loading.value = false
  }
}
async function start() {
  if (!canStart.value || starting.value) return
  starting.value = true
  error.value = ''
  const jobs: AiReviewJob[] = []
  try {
    for (const p of plans.value) {
      if (p.modelCandidates + p.reusable === 0) continue
      jobs.push(await api.startReviewPlan(p.id, true))
    }
    emit('started', jobs)
    emit('close')
  } catch (e) {
    error.value = `${jobs.length ? `已提交 ${jobs.length} 个批次，可查看原任务进度；` : ''}${(e as Error).message}`
    if (jobs.length) emit('started', jobs)
  } finally {
    starting.value = false
  }
}
watch([includeDependencies], () => {
  generation++
  plans.value = []
  loading.value = false
})
watch(
  () => [props.open, props.runs.map((r) => r.runId).join(','), props.findingId, props.failedOnly],
  async () => {
    if (!props.open) {
      generation++
      return
    }
    try {
      await preview()
    } catch (e) {
      error.value = (e as Error).message
    }
  },
  { immediate: true },
)
</script>
<template>
  <AppModal :open="open" title="规则预审与模型审核" :busy="starting" @close="emit('close')">
    <div class="page-stack">
      <p>
        先做本地规则预审。编码规范不调用模型，未分类规则保留人工处理；依赖默认核对本地版本依据。来源问题与人工结论均保留。
      </p>
      <label class="row-actions"
        ><input type="checkbox" v-model="includeDependencies" />
        依赖利用条件存在疑问时，也允许纳入模型候选</label
      >
      <div class="row-actions">
        <button class="button secondary" :disabled="loading || starting" @click="preview">
          {{ loading ? '正在本地预审…' : '重新预审' }}
        </button>
      </div>
      <div v-if="error" class="error-banner">{{ error }}</div>
      <template v-if="plans.length">
        <div class="notice">
          原始 {{ totals.total }} 条 · 编码规范 {{ totals.quality }} 条 · 安全候选
          {{ totals.security }} 条 · 待分类
          {{ totals.audit }} 条。规范、未知规则及证据不足项不会因此被标成误报。
        </div>
        <div class="table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th>来源扫描</th>
                <th>本地预审 / 人工</th>
                <th>本批已有审核</th>
                <th>可复用组</th>
                <th>新增模型组</th>
                <th>预计首轮请求</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in plans" :key="p.id">
                <td>
                  {{ runs.find((r) => r.runId === p.scanRunId)?.assetName }} · #{{ p.scanRunId }}
                </td>
                <td>{{ p.localPrecheck }}</td>
                <td>{{ p.alreadyReviewed }}</td>
                <td>{{ p.reusable }}</td>
                <td>{{ p.modelCandidates }}</td>
                <td>{{ p.estimatedRequests }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p>
          预计新增
          {{ totals.requests }}
          次模型请求（每组双轮，失败或截断时按原有策略有限重试）。点击提交后在后台执行，可随时查看进度或手动停止。
        </p>
        <p v-if="!totals.candidates && !totals.reuse" class="muted">
          无需新增模型调用：规范已分流，其余项已审核或保留人工复核。
        </p>
      </template>
      <div class="form-actions">
        <button class="button secondary" :disabled="starting" @click="emit('close')">关闭</button
        ><button class="button primary" :disabled="!canStart || starting" @click="start">
          {{ starting ? '正在提交…' : totals.candidates ? '提交模型审核' : '仅复用已有结果' }}
        </button>
      </div>
    </div>
  </AppModal>
</template>
