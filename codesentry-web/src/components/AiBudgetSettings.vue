<script setup lang="ts">
import { ref,onMounted } from 'vue'
import { api } from '@/api/codesentry'
import type { ReviewBudgetPolicy } from '@/types'
const policy=ref<ReviewBudgetPolicy>(),busy=ref(false),error=ref(''),message=ref(''),currentModel=ref('')
onMounted(async()=>{try{const [p,config]=await Promise.all([api.reviewBudgetPolicy(),api.aiReviewConfig()]);policy.value=p;currentModel.value=config.model??'未配置模型'}catch(e){error.value=(e as Error).message}})
async function save(){if(!policy.value)return;busy.value=true;error.value='';message.value='';try{policy.value=await api.saveReviewBudgetPolicy(policy.value);message.value='预算已保存；既有预览需要重新生成，正在执行的批次保留已批准的单价及额度。'}catch(e){error.value=(e as Error).message}finally{busy.value=false}}
</script>
<template>
 <section class="panel panel-pad"><div class="section-heading"><div><h3>模型审核费用控制</h3><p>编码规范和规则预审不调用模型。付费审核默认关闭；切换模型后需要重新维护单价。金额单位为人民币。</p></div></div>
 <div v-if="error" class="error-banner">{{error}}</div><div v-if="message" class="notice">{{message}}</div>
 <p>当前模型：{{currentModel}}<template v-if="policy?.pricedModel"> · 已保存单价对应：{{policy.pricedModel}}</template></p><form v-if="policy" class="page-stack" @submit.prevent="save">
  <label class="row-actions"><input type="checkbox" v-model="policy.paidEnabled" /> 允许在明确预览和确认预算后使用付费模型</label>
  <div class="form-grid">
   <div class="field"><label>输入单价（元 / 百万 Token）</label><input class="input" type="number" min="0" step="0.0001" v-model.number="policy.inputPricePerMillion" /></div>
   <div class="field"><label>输出单价（元 / 百万 Token）</label><input class="input" type="number" min="0" step="0.0001" v-model.number="policy.outputPricePerMillion" /></div>
   <div class="field"><label>单批次最大费用（元）</label><input class="input" type="number" min="0.01" step="0.01" v-model.number="policy.maxCost" /></div>
   <div class="field"><label>每日总费用预算（元）</label><input class="input" type="number" min="0.01" step="0.01" v-model.number="policy.dailyCostLimit" /></div>
   <div class="field"><label>单批次最大请求数（含重试）</label><input class="input" type="number" min="1" max="1000" v-model.number="policy.maxRequests" /></div>
   <div class="field"><label>单批次输入 Token 预算</label><input class="input" type="number" min="1" v-model.number="policy.maxInputTokens" /></div>
   <div class="field"><label>单批次输出 Token 预算</label><input class="input" type="number" min="1" v-model.number="policy.maxOutputTokens" /></div>
   <div class="field"><label>每次请求最大输出 Token</label><input class="input" type="number" min="1024" max="8192" v-model.number="policy.outputTokensPerRequest" /></div>
  </div>
  <p class="muted">输入按请求 UTF-8 字节数保守占额，输出按最大额度占额；失败请求、重试及在途请求不退占额。另记录厂商返回的实际 usage，未返回时标识未知，不能当作零费用。达到额度停止提交新请求，待处理问题保留。</p>
  <div class="form-actions"><button class="button primary" :disabled="busy">{{busy?'正在保存…':'保存审核预算'}}</button></div>
 </form></section>
</template>
