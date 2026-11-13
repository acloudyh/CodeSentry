<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
const props = withDefaults(defineProps<{ open: boolean; title: string; busy?: boolean }>(), { busy: false })
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
function close() { if (!props.busy) emit('close') }
async function syncOpen() {
  await nextTick()
  const element = dialog.value
  if (props.open && element?.isConnected && !element.open) element.showModal()
  else if (!props.open && element?.open) element.close()
}
watch(() => props.open, syncOpen, { flush: 'post' })
onMounted(syncOpen)
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="project-modal" :aria-label="title" :aria-busy="busy" @cancel.prevent="close" @click.self="close">
      <div class="project-modal-content">
        <header class="project-modal-head"><h2>{{ title }}</h2><button type="button" class="icon-button" aria-label="关闭弹窗" :disabled="busy" @click="close">×</button></header>
        <slot />
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.project-modal { width: min(900px, calc(100vw - 48px)); max-height: calc(100dvh - 48px); padding: 0; margin: auto; overflow-y: auto; overscroll-behavior: contain; border: 1px solid var(--line-strong); border-radius: 12px; background: var(--panel); color: var(--paper); box-shadow: 0 24px 90px rgba(0,0,0,.24); }
.project-modal::backdrop { background: rgba(30,42,55,.42); backdrop-filter: blur(3px); }
.project-modal-content { padding: 24px; }
.project-modal-head { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 20px; }
.project-modal-head h2 { margin: 0; font-size: 20px; }
.project-modal-head .icon-button { flex: none; font-size: 24px; }
@media (max-width: 640px) { .project-modal { width: calc(100vw - 24px); max-height: calc(100dvh - 24px); } .project-modal-content { padding: 20px; } }
</style>
