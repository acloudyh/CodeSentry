<script setup lang="ts">
defineProps<{ value?: string }>()

const labels: Record<string, string> = {
  PENDING: '等待中', PREPARING: '准备中', RUNNING: '扫描中', COMPLETED: '已完成',
  PARTIAL: '部分完成', FAILED: '失败', CANCELLED: '已取消',
  SKIPPED: '已跳过',
  NEW: '新增', EXISTING: '存量', FIXED: '已修复', REOPENED: '重新出现',
  CRITICAL: '严重', HIGH: '高危', MEDIUM: '中危', LOW: '低危', INFO: '提示',
  CONFIRMED: '确认风险', FALSE_POSITIVE: '误报', ACCEPTED_RISK: '接受风险', FIXED_PENDING: '已整改待验证',
}
</script>

<template>
  <span class="status-badge" :class="`status-${(value ?? 'UNKNOWN').toLowerCase()}`">
    <i></i>{{ labels[value ?? ''] ?? value ?? '未知' }}
  </span>
</template>
