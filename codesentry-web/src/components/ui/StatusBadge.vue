<script setup lang="ts">
defineProps<{ value?: string }>()
import { statusLabel } from '@/utils/labels'
</script>

<template>
  <span class="status-badge" :class="`status-${(value ?? 'UNKNOWN').toLowerCase()}`">
    <i></i>{{ statusLabel(value) }}
  </span>
</template>
