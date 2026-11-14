<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/api/codesentry'
import type { ScanProject } from '@/types'
withDefaults(defineProps<{ idPrefix?: string }>(), { idPrefix: 'new-jar' })
const emit = defineEmits<{ created: [run: ScanProject]; busy: [value: boolean]; cancel: [] }>()
const file = ref<File | null>(null)
const name = ref('')
const javaVersion = ref<number | null>(null)
const saving = ref(false)
const error = ref('')
function select(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0] ?? null
  file.value = null
  error.value = ''
  if (
    selected &&
    (!selected.name.toLowerCase().endsWith('.jar') ||
      !selected.size ||
      selected.size > 2 * 1024 * 1024 * 1024)
  ) {
    input.value = ''
    error.value = '请选择有效的 .jar 文件，大小不超过 2 GB（2048 MB）'
    return
  }
  file.value = selected
}
async function submit() {
  if (!file.value || saving.value) return
  saving.value = true
  error.value = ''
  emit('busy', true)
  try {
    emit('created', await api.createJarProject(file.value, name.value, javaVersion.value))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'JAR 上传失败'
  } finally {
    saving.value = false
    emit('busy', false)
  }
}
</script>
<template>
  <form class="form-grid jar-upload-form" @submit.prevent="submit">
    <div v-if="error" class="error-banner span-2" role="alert">{{ error }}</div>
    <div class="field span-2">
      <label :for="`${idPrefix}-file`">选择 JAR 文件</label
      ><input
        :id="`${idPrefix}-file`"
        type="file"
        accept=".jar"
        required
        :disabled="saving"
        @change="select"
      /><small>{{
        file
          ? `${file.name} · ${(file.size / 1024 / 1024).toFixed(1)} MB`
          : '支持普通 JAR、Spring Boot JAR，单个文件最大 2 GB（2048 MB）。'
      }}</small>
    </div>
    <div class="field">
      <label :for="`${idPrefix}-name`">项目名称（选填）</label
      ><input
        :id="`${idPrefix}-name`"
        v-model.trim="name"
        class="input"
        maxlength="80"
        placeholder="默认使用文件名"
        :disabled="saving"
      />
    </div>
    <div class="field">
      <label :for="`${idPrefix}-java`">业务系统 Java 版本</label
      ><select :id="`${idPrefix}-java`" v-model="javaVersion" class="select" :disabled="saving">
        <option :value="null">未确认</option>
        <option v-for="version in [8, 11, 17, 21, 25]" :key="version" :value="version">
          Java {{ version }}
        </option>
      </select>
    </div>
    <div class="field span-2 notice">
      上传后保存为 JAR 项目。在“发起扫描”中选择该项目，即可重复扫描，无需再次上传。
    </div>
    <div v-if="saving" class="field span-2 notice" role="status">
      正在上传并保存项目，请保持弹窗打开。
    </div>
    <div class="form-actions field span-2">
      <button type="button" class="button secondary" :disabled="saving" @click="emit('cancel')">
        取消</button
      ><button class="button primary" :disabled="saving || !file">
        {{ saving ? '正在上传…' : '确认新增' }}
      </button>
    </div>
  </form>
</template>
<style scoped>
.jar-upload-form .field {
  align-content: start;
}
.jar-upload-form .input,
.jar-upload-form .select {
  height: 44px;
}
.jar-upload-form .span-2 {
  grid-column: 1 / -1;
}
</style>
