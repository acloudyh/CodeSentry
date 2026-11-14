<script setup lang="ts">
import { computed } from 'vue'

/** 扫描记录与整改报告共用的联网核实状态；与扫描执行状态独立展示。 */
const props = defineProps<{ networkMode?: string; status?: string; message?: string }>()
const state = computed(() => {
  if (props.networkMode !== 'ONLINE') return { code: 'unknown', label: '未启用' }
  const labels: Record<string, string> = {
    PENDING: '等待核实',
    RUNNING: '核实中',
    COMPLETED: '已完成',
    PARTIAL: '部分完成',
    FAILED: '失败',
    CANCELLED: '已停止',
  }
  return {
    code: (props.status ?? 'unknown').toLowerCase(),
    label: labels[props.status ?? ''] ?? '未核实',
  }
})
</script>

<template>
  <span
    class="status-badge verification-status"
    :class="`status-${state.code}`"
    :title="message || state.label"
    ><i></i>{{ state.label }}</span
  >
</template>

<style scoped>
.verification-status {
  white-space: nowrap;
}
</style>
