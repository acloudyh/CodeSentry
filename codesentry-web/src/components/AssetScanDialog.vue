<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppModal from '@/components/ui/AppModal.vue'
import { api } from '@/api/codesentry'
import { repositoryReady, runtimeVersionLabel } from '@/utils/labels'
import type {
  BranchView,
  RuleProfile,
  ScanMode,
  ScanProject,
  ScanRun,
  WorkspaceMode,
} from '@/types'
const props = withDefaults(
  defineProps<{
    asset: ScanProject
    initialNetworkMode?: 'LOCAL' | 'ONLINE'
    initialRuleProfile?: string
    sourceReportRunId?: number
  }>(),
  { initialNetworkMode: 'LOCAL', initialRuleProfile: '' },
)
const emit = defineEmits<{ close: []; started: [run: ScanRun] }>()
const loading = ref(true),
  submitting = ref(false),
  error = ref(''),
  branchError = ref('')
const profiles = ref<RuleProfile[]>([]),
  branches = ref<BranchView[]>([])
const isJar = computed(() => props.asset.sourceType === 'JAR_UPLOAD')
const title = computed(() => (props.sourceReportRunId ? '发起验收复扫' : '发起资产扫描'))
const name = computed(
  () => props.asset.displayName || props.asset.projectName.replace(/ · [0-9a-f-]{36}$/i, ''),
)
const form = reactive({
  networkMode: props.initialNetworkMode,
  scanMode: 'FULL' as ScanMode,
  workspaceMode: (isJar.value
    ? 'JAR_UPLOAD'
    : props.asset.sourceType === 'LOCAL'
      ? 'CURRENT'
      : 'BRANCH_SNAPSHOT') as WorkspaceMode,
  scanRef: props.asset.defaultBranch ?? '',
  baselineRef: props.asset.defaultBranch ?? '',
  ruleProfile: props.initialRuleProfile,
  refreshRemote: false,
})
onMounted(async () => {
  const results = await Promise.allSettled([
    api.ruleProfiles(),
    isJar.value ? Promise.resolve([] as BranchView[]) : api.branches(props.asset.id, false),
  ])
  const rules = results[0],
    refs = results[1]
  if (rules.status === 'fulfilled') {
    profiles.value = rules.value
    if (props.sourceReportRunId && !profiles.value.some((p) => p.id === props.initialRuleProfile))
      error.value = '原报告规则集不可用，请先检查规则配置。'
    else if (!profiles.value.some((p) => p.id === form.ruleProfile))
      form.ruleProfile =
        profiles.value.find((p) => p.id === (isJar.value ? 'COMMUNITY_GENERAL' : 'LOCAL_OWASP'))
          ?.id ??
        profiles.value[0]?.id ??
        ''
  } else error.value = rules.reason instanceof Error ? rules.reason.message : '规则集加载失败'
  if (refs.status === 'fulfilled') {
    branches.value = refs.value
    const branch = refs.value.some((b) => b.name === props.asset.defaultBranch)
      ? props.asset.defaultBranch!
      : (refs.value[0]?.name ?? props.asset.defaultBranch ?? '')
    form.scanRef = branch
    form.baselineRef = branch
  } else
    branchError.value =
      refs.reason instanceof Error ? refs.reason.message : '分支读取失败，可检查缓存后重试'
  loading.value = false
})
async function submit() {
  if (submitting.value || loading.value || !form.ruleProfile) return
  if (!repositoryReady(props.asset)) {
    error.value = '代码尚未就绪，请等待后台拉取完成。'
    return
  }
  if (props.sourceReportRunId && form.ruleProfile !== props.initialRuleProfile) {
    error.value = '验收复扫必须沿用原报告规则集。'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const run = await api.startScan({
      projectId: props.asset.id,
      networkMode: form.networkMode,
      scanMode: isJar.value || props.sourceReportRunId ? 'FULL' : form.scanMode,
      workspaceMode: isJar.value ? 'JAR_UPLOAD' : form.workspaceMode,
      ruleProfile: form.ruleProfile,
      sourceReportRunId: props.sourceReportRunId,
      scanRef: !isJar.value && form.workspaceMode === 'BRANCH_SNAPSHOT' ? form.scanRef : undefined,
      baselineRef:
        !isJar.value && form.scanMode === 'INCREMENTAL' && !props.sourceReportRunId
          ? form.baselineRef
          : undefined,
      refreshRemote:
        props.asset.sourceType === 'GIT' && form.networkMode === 'ONLINE' && form.refreshRemote,
    })
    emit('started', run)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '扫描提交失败'
  } finally {
    submitting.value = false
  }
}
</script>
<template>
  <AppModal :open="true" :title="title" :busy="submitting" @close="emit('close')">
    <form class="page-stack" @submit.prevent="submit">
      <div class="scan-asset">
        <strong>{{ name }}</strong><span class="muted"
          >{{ isJar ? 'JAR 包' : asset.sourceType === 'GIT' ? '远程 Git 仓库' : '本地代码仓库' }} ·
          {{ runtimeVersionLabel(asset) }}</span
        ><span v-if="isJar" class="mono">{{ asset.localPath?.split('/').pop() }}</span>
      </div>
      <div v-if="sourceReportRunId" class="notice">
        报告 CSR-{{ String(sourceReportRunId).padStart(6, '0') }}
        的验收复扫：锁定原资产、原规则集和全量模式。
      </div>
      <div class="form-grid">
        <div class="field">
          <label for="asset-scan-network">扫描方式</label
          ><select
            id="asset-scan-network"
            v-model="form.networkMode"
            class="select"
            :disabled="submitting"
          >
            <option value="LOCAL">本地扫描</option>
            <option value="ONLINE">联网扫描</option>
          </select>
        </div>
        <div class="field">
          <label for="asset-scan-profile">规则集</label
          ><select
            id="asset-scan-profile"
            v-model="form.ruleProfile"
            class="select"
            :disabled="loading || submitting || !!sourceReportRunId"
            required
          >
            <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <template v-if="!isJar">
          <div class="field">
            <label for="asset-scan-mode">扫描模式</label
            ><select
              id="asset-scan-mode"
              class="select"
              v-model="form.scanMode"
              :disabled="submitting || !!sourceReportRunId"
            >
              <option value="FULL">全量扫描</option>
              <option value="INCREMENTAL">增量扫描</option>
            </select>
          </div>
          <div class="field">
            <label for="asset-scan-workspace">代码读取方式</label
            ><select
              id="asset-scan-workspace"
              class="select"
              v-model="form.workspaceMode"
              :disabled="submitting"
            >
              <option v-if="asset.sourceType === 'LOCAL'" value="CURRENT">当前工作区</option>
              <option value="BRANCH_SNAPSHOT">分支快照</option>
            </select>
          </div>
          <div v-if="form.workspaceMode === 'BRANCH_SNAPSHOT'" class="field">
            <label for="asset-scan-ref">待扫描分支</label
            ><input
              id="asset-scan-ref"
              class="input mono"
              v-model.trim="form.scanRef"
              list="asset-scan-branches"
              required
              maxlength="255"
              :disabled="submitting"
            />
          </div>
          <div v-if="form.scanMode === 'INCREMENTAL'" class="field">
            <label for="asset-scan-baseline">基线分支</label
            ><input
              id="asset-scan-baseline"
              class="input mono"
              v-model.trim="form.baselineRef"
              list="asset-scan-branches"
              required
              maxlength="255"
              :disabled="submitting"
            />
          </div>
          <datalist id="asset-scan-branches">
            <option v-for="b in branches" :key="b.name" :value="b.name" />
          </datalist>
        </template>
      </div>
      <p v-if="loading" class="muted">正在加载扫描配置…</p>
      <div v-if="branchError" class="notice">{{ branchError }}</div>
      <label v-if="asset.sourceType === 'GIT' && form.networkMode === 'ONLINE'" class="sync-option"
        ><input type="checkbox" v-model="form.refreshRemote" :disabled="submitting" />
        扫描前同步远程代码（默认使用本地缓存）</label
      >
      <p class="notice">
        {{
          isJar
            ? 'JAR 使用全量扫描，固定保存当前文件版本，无需重新上传。'
            : form.scanMode === 'INCREMENTAL'
              ? '按基线与扫描版本的共同祖先对比新增、存量和已修复问题。'
              : '扫描当前选择的代码目标，不切换原仓库分支。'
        }}<br />{{
          form.networkMode === 'LOCAL'
            ? '使用本地规则及已缓存漏洞库，不联网补查整改依据。'
            : '联网核实依赖修复版本；外部查询失败时保留本地结果并标注未核实项。'
        }}
      </p>
      <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
      <div class="form-actions">
        <button
          type="button"
          class="button secondary"
          :disabled="submitting"
          @click="emit('close')"
        >
          取消</button
        ><button
          type="submit"
          class="button primary"
          :disabled="loading || submitting || !form.ruleProfile"
        >
          {{ submitting ? '正在提交…' : '提交扫描' }}
        </button>
      </div>
    </form>
  </AppModal>
</template>
<style scoped>
.scan-asset {
  display: grid;
  gap: 8px;
  overflow-wrap: anywhere;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.scan-asset strong {
  font-size: 18px;
}
.sync-option {
  display: flex;
  align-items: center;
  gap: 8px;
}
.form-actions {
  justify-content: flex-end;
}
</style>
