<script setup lang="ts">
import { ref,onMounted } from 'vue'
import { request } from '@/api/http'
const rules=ref<{ruleId:string;issueType:string;findingCount:number;reason?:string}[]>([]),ruleId=ref(''),issueType=ref('AUDIT'),reason=ref(''),error=ref(''),message=ref(''),busy=ref(false)
async function load(){rules.value=await request('/api/v1/findings/rule-classifications')}
onMounted(()=>{void load().catch(e=>error.value=e.message)})
async function save(){busy.value=true;error.value='';message.value='';try{await request('/api/v1/findings/rule-classifications',{method:'PUT',body:JSON.stringify({ruleId:ruleId.value,issueType:issueType.value,reason:reason.value})});await load();message.value='分类已保存；未提交的审核需要重新预览，原始扫描和归档报告未改写。'}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
</script>
<template><section class="panel panel-pad page-stack"><h3>规则分类维护</h3><p>命名、注释等规范可由规则直接检查；安全规则仍需核实。新规则默认待分类。按精确规则 ID 维护，不整批忽略某个扫描器。</p><div v-if="error" class="error-banner">{{error}}</div><div v-if="message" class="notice">{{message}}</div>
<form class="page-stack" @submit.prevent="save"><div class="form-grid"><div class="field"><label>精确规则 ID</label><input class="input" v-model="ruleId" required /></div><div class="field"><label>问题分类</label><select class="select" v-model="issueType"><option value="QUALITY">编码规范</option><option value="SECURITY">安全候选</option><option value="AUDIT">待分类 / 审计线索</option></select></div><div class="field" style="grid-column:1/-1"><label>分类依据</label><input class="input" v-model="reason" required maxlength="1000" /></div></div><div class="form-actions"><button class="button primary" :disabled="busy">保存规则分类</button></div></form>
<div class="table-scroll"><table class="data-table"><thead><tr><th>已有规则（最多显示 500 条，可直接输入完整 ID）</th><th>分类</th><th>问题数</th><th>操作</th></tr></thead><tbody><tr v-for="r in rules" :key="r.ruleId+r.issueType"><td>{{r.ruleId}}</td><td>{{({QUALITY:'编码规范',SECURITY:'安全候选',AUDIT:'待分类'})[r.issueType]??r.issueType}}</td><td>{{r.findingCount}}</td><td><button type="button" class="table-action" @click="ruleId=r.ruleId;issueType=r.issueType;reason=r.reason??''">选择维护</button></td></tr></tbody></table></div></section></template>
