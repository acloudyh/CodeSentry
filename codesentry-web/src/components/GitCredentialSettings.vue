<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api } from '@/api/codesentry'
import type { GitCredentialProfile } from '@/types'
import { formatChinaTime } from '@/utils/datetime'
import AppModal from '@/components/ui/AppModal.vue'

const profiles = ref<GitCredentialProfile[]>([]), open = ref(false), busy = ref(false), error = ref(''), formError = ref('')
const editing = ref<number | null>(null)
const form = reactive({ name: '', serverUrl: '', username: '', password: '' })
/** 列表只读取元数据；密码不从接口返回或写入浏览器存储。 */
async function load() { try { profiles.value = await api.gitCredentials() } catch(e) { error.value = (e as Error).message } }
function edit(profile?: GitCredentialProfile) {
  editing.value = profile?.id ?? null
  Object.assign(form, { name: profile?.name ?? '', serverUrl: profile?.serverUrl ?? '', username: profile?.username ?? '', password: '' })
  formError.value = ''; open.value = true
}
/** 编辑时留空保留原密码；关闭弹窗即清除输入中的秘密。 */
function close() { if (!busy.value) { open.value = false; form.password = '' } }
/** 只提交本次输入的密码；编辑留空时由服务端保留原密文。 */
async function save() {
  if (busy.value) return
  busy.value = true; formError.value = ''
  try { await api.saveGitCredential(editing.value, { name: form.name.trim(), serverUrl: form.serverUrl.trim(), username: form.username.trim(), ...(form.password ? { password: form.password } : {}) }); open.value = false; form.password = ''; await load() }
  catch(e) { formError.value = (e as Error).message }
  finally { busy.value = false }
}
/** 后端会再次校验引用数，防止并发登记后误删仍被使用的账号。 */
async function remove(profile: GitCredentialProfile) {
  if (busy.value || !window.confirm(`删除 Git 账号“${profile.name}”？`)) return
  busy.value = true; error.value = ''
  try { await api.deleteGitCredential(profile.id); await load() }
  catch(e) { error.value = (e as Error).message }
  finally { busy.value = false }
}
onMounted(load)
</script>

<template>
  <section class="panel panel-pad page-stack git-credential-settings">
    <header class="section-heading"><div><h2>Git 账号管理</h2><p>统一维护 Git 服务的账号密码或访问令牌，登记仓库时直接选择。</p></div><button class="button primary" :disabled="busy" @click="edit()">+ 新增 Git 账号</button></header>
    <div v-if="error" class="error-banner" role="alert">{{ error }}</div>
    <div v-if="profiles.length" class="table-scroll"><table class="data-table git-credentials-table"><thead><tr><th>配置名称</th><th>Git 服务地址</th><th>账号</th><th>引用仓库</th><th>更新时间</th><th class="actions-heading">操作</th></tr></thead><tbody><tr v-for="profile in profiles" :key="profile.id"><td><strong>{{ profile.name }}</strong></td><td class="mono">{{ profile.serverUrl }}</td><td>{{ profile.username }}</td><td>{{ profile.usageCount }} 个</td><td class="mono datetime-cell"><time class="datetime-text">{{ formatChinaTime(profile.updatedAt) }}</time></td><td class="actions-cell"><div class="row-actions"><button class="row-action-link" :disabled="busy" @click="edit(profile)">编辑</button><button class="row-action-link danger" :disabled="busy" @click="remove(profile)">删除</button></div></td></tr></tbody></table></div>
    <p v-else class="muted">尚未维护 Git 账号。新增后，登记多个仓库无需重复输入密码。</p>
    <AppModal :open="open" :title="editing ? '编辑 Git 账号' : '新增 Git 账号'" :busy="busy" @close="close"><form class="form-grid" @submit.prevent="save">
      <div class="field"><label for="credential-name">配置名称</label><input id="credential-name" v-model.trim="form.name" class="input" required maxlength="128" :disabled="busy" placeholder="例如：研发 GitLab" /></div>
      <div class="field"><label for="credential-server">Git 服务地址</label><input id="credential-server" v-model.trim="form.serverUrl" class="input" type="url" required maxlength="2048" :disabled="busy" placeholder="https://git.example.com:8443" /><small>所选账号仅用于相同协议、主机和端口的仓库。</small></div>
      <div class="field"><label for="credential-username">Git 账号</label><input id="credential-username" v-model.trim="form.username" class="input" required maxlength="256" :disabled="busy" autocomplete="off" /></div>
      <div class="field"><label for="credential-password">密码 / 访问令牌</label><input id="credential-password" v-model="form.password" class="input" type="password" :required="!editing" maxlength="4000" :disabled="busy" autocomplete="new-password" :placeholder="editing ? '留空保持原密码' : '请输入密码或访问令牌'" /><small>加密保存且不回显；修改后，引用仓库在下次校验和同步时使用新密码。</small></div>
      <div v-if="formError" class="error-banner span-2" role="alert">{{ formError }}</div>
      <div class="form-actions"><button type="button" class="button secondary" :disabled="busy" @click="close">取消</button><button class="button primary" :disabled="busy">{{ busy ? '保存中…' : '保存账号' }}</button></div>
    </form></AppModal>
  </section>
</template>

<style scoped>
.git-credentials-table { min-width: 1050px; }
.git-credentials-table th:nth-child(4) { width: 100px; }
.git-credentials-table th:nth-child(5) { width: 200px; }
.git-credentials-table th:last-child { width: 145px; }
</style>
