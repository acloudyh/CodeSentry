<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/codesentry'
import type { ProjectScanRound, ReportPackageFormat, ReportPackageJob } from '@/types'
import AppModal from '@/components/ui/AppModal.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'

const props=defineProps<{open:boolean;projectId:number;round?:ProjectScanRound}>()
const emit=defineEmits<{close:[]}>()
const saved=localStorage.getItem('codesentry-project-zip-format')
const format=ref<ReportPackageFormat>(saved==='PDF'||saved==='BOTH'?saved:'WORD')
const job=ref<ReportPackageJob|null>(null),loading=ref(false),submitting=ref(false),error=ref('')
const eligible=computed(()=>(props.round?.items??[]).filter(a=>a.runId&&['COMPLETED','PARTIAL'].includes(a.status)))
const active=computed(()=>job.value&&['PENDING','RUNNING'].includes(job.value.status))
const expected=computed(()=>eligible.value.length*(format.value==='BOTH'?2:1))
const canStart=computed(()=>!!props.round&&!props.round.assetHistory&&props.round.status!=='RUNNING'&&eligible.value.length>0&&!loading.value&&!submitting.value&&!active.value)
const fileName=computed(()=>`${props.round?.projectName??'项目'}安全扫描.zip`)
const percent=computed(()=>job.value?.totalFiles?Math.round(job.value.completedFiles/job.value.totalFiles*100):0)
let generation=0,timer:ReturnType<typeof setTimeout>|undefined,requestKey=''
function stop(){if(timer)clearTimeout(timer);timer=undefined}
/** 弹窗关闭仅停止轮询，后台继续；再次打开按轮次与格式恢复持久化任务。 */
async function restore(){
 stop();const token=++generation;job.value=null;error.value='';loading.value=true;requestKey=''
 if(!props.open||!props.round){loading.value=false;return}
 try{const result=await api.latestReportPackage(props.projectId,props.round.id,format.value);if(token===generation){job.value=result;poll(token)}}
 catch(e){if(token===generation)error.value=(e as Error).message}
 finally{if(token===generation)loading.value=false}
}
function poll(token:number){
 if(token!==generation||!props.open||!active.value)return
 timer=setTimeout(async()=>{
  try{const result=await api.reportPackage(props.projectId,job.value!.id);if(token!==generation)return;job.value=result;error.value=''}
  catch(e){if(token===generation)error.value=`进度暂未刷新：${(e as Error).message}。后台任务继续执行。`}
  poll(token)
 },1500)
}
async function start(){
 if(!canStart.value||!props.round)return
 submitting.value=true;error.value=''
 if(!requestKey||job.value)requestKey=crypto.randomUUID()
 const token=generation
 try{const result=await api.startReportPackage(props.projectId,props.round.id,format.value,requestKey);if(token===generation){job.value=result;stop();poll(token)}}
 catch(e){if(token===generation)error.value=(e as Error).message}
 finally{submitting.value=false}
}
watch(format,value=>localStorage.setItem('codesentry-project-zip-format',value))
watch(()=>[props.open,props.projectId,props.round?.id,format.value],restore,{immediate:true})
onBeforeUnmount(()=>{generation++;stop()})
</script>

<template>
 <AppModal :open="open" title="按项目打包资产报告" :busy="submitting" @close="emit('close')">
  <div class="page-stack">
   <div class="zip-heading"><strong>{{round?.projectName}}</strong><span>项目扫描 #P{{round?.id}} · {{round?.fullScope?'全部资产':'部分资产'}}</span></div>
   <fieldset class="zip-formats"><legend>报告格式</legend><label><input type="radio" v-model="format" value="PDF" :disabled="submitting" /> 仅 PDF</label><label><input type="radio" v-model="format" value="WORD" :disabled="submitting" /> 仅 Word</label><label><input type="radio" v-model="format" value="BOTH" :disabled="submitting" /> Word＋PDF</label></fieldset>
   <div class="notice">本次可打包 {{eligible.length}} 个资产、{{expected}} 份报告，来源固定为所选轮次。失败、取消和本轮未扫描项不使用旧报告补齐；部分完成的资产会在包内注明。</div>
   <div class="table-scroll"><table class="data-table"><thead><tr><th>资产名称</th><th>类型</th><th>来源扫描</th><th>扫描状态</th><th>打包范围</th></tr></thead><tbody>
    <tr v-for="item in round?.items" :key="item.assetId"><td>{{item.assetName}}</td><td>{{item.sourceType==='JAR_UPLOAD'?'JAR':'代码'}}</td><td>{{item.runId?`#${item.runId}`:'未启动'}}</td><td><StatusBadge :value="item.status" /><div v-if="item.errorMessage" class="muted">{{item.errorMessage}}</div></td><td>{{item.runId&&['COMPLETED','PARTIAL'].includes(item.status)?'包含报告':'不包含报告'}}</td></tr>
    <tr v-for="item in round?.unselectedAssets" :key="`unselected-${item.assetId}`"><td>{{item.assetName}}</td><td>—</td><td>—</td><td>本轮未扫描</td><td>不包含报告</td></tr>
   </tbody></table></div>
   <p class="muted">{{fileName}}<br/>{{format==='BOTH'?'Word 和 PDF 分别放在 Word/、PDF/ 文件夹。':'各资产报告直接放在压缩包根目录。'}}同名资产自动追加资产编号；有不完整项时附带导出说明。</p>
   <div v-if="error" class="error-banner" role="alert">{{error}}</div>
   <div v-if="loading" class="notice" role="status">正在恢复打包任务…</div>
   <section v-if="job" class="zip-progress" aria-label="ZIP 打包进度">
    <div class="row-actions"><StatusBadge :value="job.status" /><span>已生成 {{job.completedFiles}} / {{job.totalFiles}} 份文件</span><span v-if="job.status==='COMPLETED'">{{(job.fileBytes/1048576).toFixed(2)}} MB</span></div>
    <progress :value="job.completedFiles" :max="job.totalFiles" :aria-label="`ZIP 打包进度 ${percent}%`" />
    <p v-if="job.status==='PENDING'" class="muted">任务已排队，页面可以关闭；重新打开可查看进度。</p>
    <p v-else-if="job.status==='RUNNING'" class="muted">正在后台生成报告并打包，页面可以关闭。</p>
    <p v-if="job.errorMessage" class="error-banner">{{job.errorMessage}}</p>
   </section>
   <p v-if="round?.status==='RUNNING'" class="notice">该轮扫描尚未结束，结束后才能打包。</p>
   <div class="form-actions"><button class="button secondary" :disabled="submitting" @click="emit('close')">关闭</button><a v-if="job?.status==='COMPLETED'" class="button primary" :href="api.reportPackageDownloadUrl(projectId,job.id)" download>下载 ZIP</a><button class="button" :class="job?.status==='COMPLETED'?'secondary':'primary'" :disabled="!canStart" @click="start">{{submitting?'正在提交…':active?'正在后台打包…':job?'重新生成 ZIP':'生成 ZIP'}}</button></div>
  </div>
 </AppModal>
</template>

<style scoped>
.zip-heading {display:flex;align-items:center;justify-content:space-between;gap:20px}.zip-heading span{color:var(--muted);font-size:13px}
.zip-formats{display:flex;gap:24px;align-items:center;border:1px solid var(--line);padding:16px;margin:0;border-radius:6px}.zip-formats legend{padding:0 6px;font-size:13px}.zip-formats label{display:flex;gap:8px;align-items:center;white-space:nowrap}
.zip-progress{padding:16px;border:1px solid var(--line);border-radius:6px}.zip-progress progress{width:100%;height:10px;margin:14px 0 4px;accent-color:var(--signal)}
</style>
