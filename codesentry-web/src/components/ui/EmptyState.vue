<script setup lang="ts">
defineProps<{ title: string; description: string; action?: string; to?: string }>()
</script>

<template>
  <div class="empty-state">
    <div class="empty-radar"><i></i><i></i><b></b></div>
    <h3>{{ title }}</h3>
    <p>{{ description }}</p>
    <RouterLink v-if="action && to" :to="to" class="button secondary">{{ action }}</RouterLink>
  </div>
</template>

