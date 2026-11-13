<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

defineProps<{
  label: string
  primary?: boolean
  disabled?: boolean
  items: ReadonlyArray<{ label: string; value: string }>
}>()
const emit = defineEmits<{ select: [value: string] }>()
const menuId = useId()
const trigger = ref<HTMLButtonElement | null>(null)
const menu = ref<HTMLDivElement | null>(null)
const open = ref(false)
const position = ref({ top: '0px', left: '0px' })

function close(restoreFocus = false) {
  open.value = false
  if (restoreFocus) trigger.value?.focus()
}

async function show(focusLast = false) {
  open.value = true
  await nextTick()
  if (!trigger.value || !menu.value) return
  const anchor = trigger.value.getBoundingClientRect()
  const bounds = menu.value.getBoundingClientRect()
  const top = anchor.bottom + 6 + bounds.height <= window.innerHeight - 8
    ? anchor.bottom + 6 : Math.max(8, anchor.top - bounds.height - 6)
  position.value = {
    top: `${top}px`,
    left: `${Math.max(8, Math.min(anchor.right - bounds.width, window.innerWidth - bounds.width - 8))}px`,
  }
  // 先应用定位，再移动焦点；禁止焦点滚动导致刚打开的浮层被滚动监听器关闭。
  await nextTick()
  if (!open.value || !menu.value) return
  const options = menu.value.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')
  options[focusLast ? options.length - 1 : 0]?.focus({ preventScroll: true })
}

function select(value: string) {
  close(true)
  emit('select', value)
}

function navigate(event: KeyboardEvent) {
  if (event.key === 'Escape' || event.key === 'Tab') {
    if (event.key === 'Escape') event.preventDefault()
    close(true)
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const options = [...(menu.value?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])]
  const current = options.indexOf(document.activeElement as HTMLButtonElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1
    : (current + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length
  options[next]?.focus()
}

function outside(event: PointerEvent) {
  const target = event.target as Node
  if (!trigger.value?.contains(target) && !menu.value?.contains(target)) close()
}
function scrolling(event: Event) {
  if (!menu.value?.contains(event.target as Node)) close()
}
function resize() { close() }
function cleanup() {
  document.removeEventListener('pointerdown', outside, true)
  window.removeEventListener('scroll', scrolling, true)
  window.removeEventListener('resize', resize)
}
watch(open, value => {
  cleanup()
  if (value) {
    document.addEventListener('pointerdown', outside, true)
    window.addEventListener('scroll', scrolling, true)
    window.addEventListener('resize', resize)
  }
})
onBeforeUnmount(cleanup)
</script>

<template>
  <button ref="trigger" type="button" class="button compact action-dropdown-trigger"
    :class="primary ? 'primary' : 'secondary'" :disabled="disabled"
    aria-haspopup="menu" :aria-expanded="open" :aria-controls="menuId"
    @click="open ? close() : show()" @keydown.down.prevent="show()" @keydown.up.prevent="show(true)">
    {{ label }}<span class="action-chevron" aria-hidden="true">⌄</span>
  </button>
  <Teleport to="body">
    <div v-if="open" :id="menuId" ref="menu" class="action-dropdown-menu" role="menu"
      :aria-label="label" :style="position" @keydown="navigate">
      <button v-for="item in items" :key="item.value" type="button" role="menuitem" @click="select(item.value)">
        {{ item.label }}
      </button>
    </div>
  </Teleport>
</template>

<style scoped>
.action-dropdown-trigger { display: inline-flex; align-items: center; gap: 10px; white-space: nowrap; }
.action-chevron { font-size: 14px; line-height: 1; }
.action-dropdown-menu { position: fixed; z-index: 200; min-width: 168px; max-width: calc(100vw - 16px); max-height: calc(100vh - 16px); overflow-y: auto; padding: 5px; border: 1px solid var(--line); border-radius: 7px; background: var(--panel); color: var(--paper); box-shadow: 0 8px 24px rgba(20, 30, 60, .14); }
.action-dropdown-menu button { display: block; width: 100%; padding: 10px 13px; border: 0; border-radius: 4px; text-align: left; background: transparent; color: inherit; font: inherit; font-size: 13px; cursor: pointer; }
.action-dropdown-menu button:hover, .action-dropdown-menu button:focus-visible { background: var(--panel-lift); color: var(--signal); }
</style>
