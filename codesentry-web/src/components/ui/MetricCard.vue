<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  note: string
  tone?: 'signal' | 'danger' | 'calm' | 'neutral'
  index: string
}>()
</script>

<template>
  <article class="metric-card" :class="`tone-${tone ?? 'neutral'}`">
    <div class="metric-index">{{ index }}</div>
    <div class="metric-copy">
      <span>{{ label }}</span>
      <strong>{{ value }}</strong>
      <small>{{ note }}</small>
    </div>
    <div class="metric-trace"><i></i><i></i><i></i><i></i><i></i></div>
  </article>
</template>

