package io.codesentry.ai.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codesentry.ai.dto.*;
import io.codesentry.ai.model.*;
import io.codesentry.ai.provider.AiReviewProvider;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.finding.model.FindingView;
import io.codesentry.finding.service.*;
import io.codesentry.project.service.ScanProjectService;
import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.service.ScanRunService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/** 审核预览、明确付费授权、来源可追溯的结果复用及保守预算记账，均不修改人工复核结论。 */
@Service
public class AiReviewCostControl {
    public static class Policy {
        public boolean paidEnabled=false;
        public String pricedModelSignature,pricedModel,pricedProvider;
        public BigDecimal inputPricePerMillion=BigDecimal.ZERO,outputPricePerMillion=BigDecimal.ZERO;
        public BigDecimal maxCost=new BigDecimal("5"),dailyCostLimit=new BigDecimal("20");
        public int maxRequests=100,maxInputTokens=2000000,maxOutputTokens=100000,outputTokensPerRequest=1024;
    }
    public static class Options {
        public Long findingId;
        public boolean failedOnly=false,includeDependencies=false;
        public Integer maxRequests,maxInputTokens,maxOutputTokens;
        public BigDecimal maxCost;
    }
    public static class Plan {
        public String id,sourceHash,model,provider,modelSignature,policyVersion=FindingClassification.version();
        public Long scanRunId,jobId;
        public Policy budget;
        public Options options;
        public int total,quality,security,audit,localPrecheck,alreadyReviewed,reusable,modelCandidates;
        public long estimatedInputTokens,estimatedOutputTokens;
        public int estimatedRequests;
        public BigDecimal estimatedCost;
        public LocalDateTime expiresAt;
        public List<Long> findingIds=new ArrayList<>();
    }
    public static class Cached {
        public Long sourceRunId,sourceFindingId;
        public AiReviewDecision primary,verification;
    }
    private final JdbcTemplate db; private final ObjectMapper json; private final FindingPersistenceService findings;
    private final ScanRunService runs; private final ScanProjectService projects; private final AiReviewEvidenceBuilder evidence;
    private final AiReviewProvider provider; private final CodeSentryProperties properties; private final TransactionTemplate tx;
    public AiReviewCostControl(JdbcTemplate db,ObjectMapper json,FindingPersistenceService findings,ScanRunService runs,
            ScanProjectService projects,AiReviewEvidenceBuilder evidence,AiReviewProvider provider,CodeSentryProperties properties,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.db=db;this.json=json;this.findings=findings;this.runs=runs;this.projects=projects;this.evidence=evidence;
        this.provider=provider;this.properties=properties;this.tx=new TransactionTemplate(transactions);
    }
    /** 默认关闭自动付费；单价由用户维护，不把未知厂商价格当作零费用。 */
    public Policy policy() {
        List<String> rows=db.queryForList("SELECT content_json FROM ai_review_cost_policy WHERE id=1",String.class);
        return rows.isEmpty()?new Policy():read(rows.get(0),Policy.class);
    }
    public Policy savePolicy(Policy policy) {
        policy.pricedModelSignature=modelSignature();policy.pricedModel=provider.modelName();policy.pricedProvider=provider.providerName();
        validate(policy);
        db.update("INSERT INTO ai_review_cost_policy(id,content_json,updated_at) VALUES(1,?,NOW(3)) ON DUPLICATE KEY UPDATE content_json=VALUES(content_json),updated_at=NOW(3)",write(policy));
        return policy;
    }
    private void validate(Policy p) {
        if(p.maxRequests<1 || p.maxRequests>1000 || p.maxInputTokens<1 || p.maxInputTokens>10000000
                || p.maxOutputTokens<1 || p.maxOutputTokens>10000000 || p.outputTokensPerRequest<1024 || p.outputTokensPerRequest>8192
                || !positive(p.maxCost) || !positive(p.dailyCostLimit) || p.inputPricePerMillion==null || p.outputPricePerMillion==null
                || p.inputPricePerMillion.signum()<0 || p.outputPricePerMillion.signum()<0
                || p.maxCost.compareTo(new BigDecimal("1000"))>0 || p.dailyCostLimit.compareTo(new BigDecimal("10000"))>0)
            throw bad("审核预算参数无效，请检查请求数、Token、费用和单价");
        if(p.paidEnabled && (!positive(p.inputPricePerMillion)||!positive(p.outputPricePerMillion))) throw bad("启用付费审核前请维护当前模型的输入和输出单价");
    }
    private boolean positive(BigDecimal n) {return n!=null&&n.signum()>0;}
    /** 预览只做本地查询，先分流后回读少量安全候选源码，不发送任何模型请求。 */
    public Plan preview(Long runId,Options options,List<AiReviewSummary> reviews) {
        if(options==null) options=new Options();
        Plan p=calculate(runId,options,reviews);p.id=UUID.randomUUID().toString();p.expiresAt=LocalDateTime.now().plusMinutes(15);
        db.update("INSERT INTO ai_review_plan(id,scan_run_id,content_json,created_at,expires_at) VALUES(?,?,?,NOW(3),?)",p.id,runId,write(p),p.expiresAt);
        return p;
    }
    private Plan calculate(Long runId,Options options,List<AiReviewSummary> reviews) {
        ScanRun run=runs.requireById(runId);
        if(!Set.of("COMPLETED","PARTIAL").contains(run.getStatus().name())) throw bad("扫描结束后才能预审");
        Plan p=new Plan();p.scanRunId=runId;p.options=options;p.budget=policy();if(!Objects.equals(p.budget.pricedModelSignature,modelSignature()))p.budget.paidEnabled=false;p.model=provider.modelName();p.provider=provider.providerName();p.modelSignature=modelSignature();
        if(options.maxRequests!=null) p.budget.maxRequests=Math.min(p.budget.maxRequests,options.maxRequests);
        if(options.maxInputTokens!=null) p.budget.maxInputTokens=Math.min(p.budget.maxInputTokens,options.maxInputTokens);
        if(options.maxOutputTokens!=null) p.budget.maxOutputTokens=Math.min(p.budget.maxOutputTokens,options.maxOutputTokens);
        if(options.maxCost!=null) p.budget.maxCost=p.budget.maxCost.min(options.maxCost);
        validate(p.budget);
        List<FindingView> raw=findings.findAuditInputsByRunId(runId);
        Map<Long,AiReviewSummary> known=reviews.stream().collect(Collectors.toMap(AiReviewSummary::getFindingId,s->s,(a,b)->a));
        List<FindingView> eligible=new ArrayList<>();
        for(FindingView f:raw) {
            if(options.findingId!=null&&!options.findingId.equals(f.getId())) continue;
            p.total++;String type=FindingClassification.type(f);
            if("QUALITY".equals(type)) {p.quality++;continue;}
            if("AUDIT".equals(type)) {p.audit++;continue;}
            p.security++;
            if("FIXED".equals(f.getResultStatus()) || "FALSE_POSITIVE".equals(f.getTriageStatus()) || "CONFIRMED".equals(f.getTriageStatus())) {p.localPrecheck++;continue;}
            AiReviewSummary done=known.get(f.getId());
            if(done!=null&&Set.of("COMPLETED","NEEDS_HUMAN").contains(done.getStatus())&&validExisting(done)) {p.alreadyReviewed++;continue;}
            if(options.failedOnly && (done==null||!"FAILED".equals(done.getStatus()))) continue;
            findings.enrichSnippet(f);
            String route=FindingClassification.precheck(f).get("route");
            if("MODEL_CANDIDATE".equals(route)||options.includeDependencies&&"LOCAL_EVIDENCE".equals(route)) eligible.add(f);
            else p.localPrecheck++;
        }
        // 原问题 ID 保留独立身份；完全等价的同批输入共享一次审核，依赖合并身份额外包含所有 CVE。
        Map<String,List<FindingView>> grouped=units(eligible);
        StringBuilder signature=new StringBuilder(write(p.budget)).append(p.model).append(p.provider).append(FindingClassification.version());
        for(List<FindingView> unit:grouped.values()) {
            AiReviewEvidence ev=unitEvidence(unit);String key=cacheKey(runId,ev);Cached hit=lookup(key);
            p.findingIds.addAll(unit.stream().map(FindingView::getId).collect(Collectors.toList()));
            signature.append(evidence.serialize(ev)).append(key).append(hit==null?"new":"cached");
            if(hit!=null)p.reusable++;
            else {p.modelCandidates++;p.estimatedInputTokens+=evidence.serialize(ev).getBytes(StandardCharsets.UTF_8).length*2L + p.budget.outputTokensPerRequest*12L + 24000;}
        }
        p.estimatedRequests=p.modelCandidates*2;
        p.estimatedOutputTokens=(long)p.estimatedRequests*p.budget.outputTokensPerRequest;
        p.estimatedCost=cost(p.estimatedInputTokens,p.estimatedOutputTokens,p.budget);
        p.sourceHash=evidence.sha256(signature.toString());return p;
    }
    private boolean validExisting(AiReviewSummary s) {
        AiReviewRecord a=s.getPrimaryReview(),b=s.getVerificationReview();
        return a!=null&&b!=null&&AiReviewService.PROMPT_VERSION.equals(a.getPromptVersion())&&AiReviewService.PROMPT_VERSION.equals(b.getPromptVersion())
                &&Objects.equals(a.getReviewJobId(),b.getReviewJobId())&&a.getInputHash()!=null&&a.getInputHash().equals(b.getInputHash())
                &&Objects.equals(provider.modelName(),a.getModel())&&Objects.equals(provider.modelName(),b.getModel())
                &&Objects.equals(provider.providerName(),a.getProvider())&&Objects.equals(provider.providerName(),b.getProvider())
                &&"COMPLETED".equals(a.getStatus())&&"COMPLETED".equals(b.getStatus());
    }
    /** 审批绑定具体来源与预算；过期或上下文变化必须重新预览，重复提交返回同一任务。 */
    public Plan approve(String id,boolean allowPaid,List<AiReviewSummary> reviews) {
        Plan saved=require(id);
        if(saved.jobId!=null)return saved;
        if(saved.expiresAt.isBefore(LocalDateTime.now()))throw bad("审核预览已过期，请重新预览");
        Plan current=calculate(saved.scanRunId,saved.options,reviews);
        if(!current.sourceHash.equals(saved.sourceHash))throw bad("审核范围、已有结果或模型配置已变化，请重新预览");
        if(current.modelCandidates>0&&(!allowPaid||!current.budget.paidEnabled))throw bad("付费审核未授权，请先启用预算并明确确认本次模型调用");
        return saved;
    }
    public Plan require(String id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT content_json,job_id,expires_at FROM ai_review_plan WHERE id=?",id);
        if(rows.isEmpty())throw bad("审核预览不存在");
        Plan p=read((String)rows.get(0).get("content_json"),Plan.class);p.jobId=rows.get(0).get("job_id")==null?null:((Number)rows.get(0).get("job_id")).longValue();
        Object expiry=rows.get(0).get("expires_at");p.expiresAt=expiry instanceof LocalDateTime?(LocalDateTime)expiry:((java.sql.Timestamp)expiry).toLocalDateTime();return p;
    }
    public void bind(Plan p,Long jobId) {
        tx.execute(status->{
            if(db.update("UPDATE ai_review_plan SET job_id=? WHERE id=? AND job_id IS NULL",jobId,p.id)!=1)throw bad("该预览已经提交，请刷新审核进度");
            db.update("INSERT INTO ai_review_budget(job_id,plan_id,content_json) VALUES(?,?,?)",jobId,p.id,write(p.budget));return null;
        });
    }
    public Map<String,Object> usage(Long jobId) {
        List<Map<String,Object>> rows=db.queryForList("SELECT requests_used,input_reserved,output_reserved,cost_reserved,input_actual,output_actual,usage_missing,content_json FROM ai_review_budget WHERE job_id=?",jobId);
        if(rows.isEmpty())return Map.of();Map<String,Object> data=rows.get(0);data.put("limits",read((String)data.remove("content_json"),Policy.class));data.put("reusedFindings",db.queryForObject("SELECT COUNT(*) FROM ai_review_reuse_log WHERE job_id=?",Integer.class,jobId));return data;
    }
    /** 先按最坏输入字节数和最大输出额度占预算；失败与重试不退占额，防止并发在途突破上限。 */
    public void reserve(Long jobId,String request,int outputLimit) {
        long input=request.getBytes(StandardCharsets.UTF_8).length;
        tx.execute(status->{
            Map<String,Object> row=db.queryForMap("SELECT * FROM ai_review_budget WHERE job_id=? FOR UPDATE",jobId);
            Policy p=read((String)row.get("content_json"),Policy.class);
            if(!policy().paidEnabled)throw new BudgetExceeded("系统付费审核已关闭，停止提交新请求");
            BigDecimal charge=cost(input,outputLimit,p);
            if(((Number)row.get("requests_used")).intValue()+1>p.maxRequests || ((Number)row.get("input_reserved")).longValue()+input>p.maxInputTokens
                    ||((Number)row.get("output_reserved")).longValue()+outputLimit>p.maxOutputTokens
                    ||((BigDecimal)row.get("cost_reserved")).add(charge).compareTo(p.maxCost)>0)throw new BudgetExceeded("本批审核达到请求、Token 或费用预算，未处理项保留待复核");
            db.update("INSERT IGNORE INTO ai_review_daily_budget(spend_date,cost_reserved) VALUES(CURRENT_DATE(),0)");
            BigDecimal daily=db.queryForObject("SELECT cost_reserved FROM ai_review_daily_budget WHERE spend_date=CURRENT_DATE() FOR UPDATE",BigDecimal.class);
            if(daily.add(charge).compareTo(policy().dailyCostLimit)>0)throw new BudgetExceeded("今日审核预算已用尽，未处理项保留待复核");
            db.update("UPDATE ai_review_daily_budget SET cost_reserved=cost_reserved+? WHERE spend_date=CURRENT_DATE()",charge);
            db.update("UPDATE ai_review_budget SET requests_used=requests_used+1,usage_missing=usage_missing+1,input_reserved=input_reserved+?,output_reserved=output_reserved+?,cost_reserved=cost_reserved+? WHERE job_id=?",input,outputLimit,charge,jobId);return null;
        });
    }
    public void recordUsage(Long jobId,JsonNode usage) {
        if(usage.path("prompt_tokens").isIntegralNumber()&&usage.path("completion_tokens").isIntegralNumber()&&usage.path("prompt_tokens").asLong()>=0&&usage.path("completion_tokens").asLong()>=0)db.update("UPDATE ai_review_budget SET usage_missing=GREATEST(0,usage_missing-1),input_actual=input_actual+?,output_actual=output_actual+? WHERE job_id=?",Math.max(0,usage.path("prompt_tokens").asLong()),Math.max(0,usage.path("completion_tokens").asLong()),jobId);
    }
    public int outputLimit(Long jobId) {return read(db.queryForObject("SELECT content_json FROM ai_review_budget WHERE job_id=?",String.class,jobId),Policy.class).outputTokensPerRequest;}
    private BigDecimal cost(long input,long output,Policy p) {return p.inputPricePerMillion.multiply(BigDecimal.valueOf(input)).add(p.outputPricePerMillion.multiply(BigDecimal.valueOf(output))).divide(new BigDecimal("1000000"),8,java.math.RoundingMode.UP);}
    public static class BudgetExceeded extends RuntimeException {public BudgetExceeded(String message){super(message);}}
    /** 仅同资产、干净的完整提交/制品摘要和相同模型/规则/上下文允许跨扫描复用。 */
    public String cacheKey(Long runId,AiReviewEvidence ev) {
        String path=ev.getFilePath();
        // 被隐去目录或无法定位到快照相对路径时，不根据同名文件猜测输入等价。
        if(path==null||path.equals("unknown")||path.startsWith("<")||path.startsWith("/")||Arrays.asList(path.split("/")).contains(".."))return null;
        ScanRun run=runs.requireById(runId);String commit=run.getScanCommit();
        if(commit==null||!commit.matches("(?i)[0-9a-f]{40}|[0-9a-f]{64}"))return null;
        if(run.getWorkspaceMode()==io.codesentry.scan.model.WorkspaceMode.JAR_UPLOAD){if(!commit.matches("(?i)[0-9a-f]{64}"))return null;}
        else if(!Boolean.FALSE.equals(run.getRepositoryDirty()))return null;
        ObjectNode canonical=json.valueToTree(ev);canonical.remove(List.of("findingId","scanRunId"));
        var project=projects.requireById(run.getProjectId());
        return evidence.sha256(run.getProjectId()+"|"+commit+"|"+run.getScanRef()+"|"+run.getBaselineRef()+"|"+run.getRuleProfile()+"|"+run.getNetworkMode()
                +"|"+project.getApplicationType()+"|"+project.getTargetJavaVersion()+"|"+project.getTargetNodeVersion()+"|"+AiReviewService.PROMPT_VERSION+"|"+FindingClassification.version()
                +"|"+provider.providerName()+"|"+provider.modelName()+"|"+properties.getAi().getBaseUrl()+"|"+canonical);
    }
    private String modelSignature(){return evidence.sha256(provider.providerName()+"|"+provider.modelName()+"|"+properties.getAi().getBaseUrl());}
    /** 执行中切换模型配置时不向新模型名下写入旧模型结果。 */
    public String jobCacheKey(Long jobId,Long runId,AiReviewEvidence ev,AiReviewProvider selected) {
        String planId=db.queryForObject("SELECT plan_id FROM ai_review_budget WHERE job_id=?",String.class,jobId);
        Plan plan=require(planId);
        if(!Objects.equals(plan.model,selected.modelName()) || !Objects.equals(plan.provider,selected.providerName())
                || !Objects.equals(plan.modelSignature,modelSignature()))return null;
        return cacheKey(runId,ev);
    }
    public Cached lookup(String key) {
        if(key==null)return null;
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM ai_review_result_cache WHERE cache_key=? AND created_at>DATE_SUB(NOW(),INTERVAL 30 DAY)",key);
        if(rows.isEmpty())return null;Map<String,Object> r=rows.get(0);Cached c=new Cached();c.sourceRunId=((Number)r.get("source_run_id")).longValue();c.sourceFindingId=((Number)r.get("source_finding_id")).longValue();c.primary=read((String)r.get("primary_json"),AiReviewDecision.class);c.verification=read((String)r.get("verify_json"),AiReviewDecision.class);return c;
    }
    /** 双轮记录与复用来源一起提交，不能出现已有正式结论却缺失来源的半完成状态。 */
    public <T>T persistResults(java.util.function.Supplier<T> action){return tx.execute(status->action.get());}
    public void cache(String key,FindingView f,AiReviewDecision primary,AiReviewDecision verify) {
        if(key!=null)db.update("INSERT INTO ai_review_result_cache(cache_key,source_run_id,source_finding_id,primary_json,verify_json,created_at) VALUES(?,?,?,?,?,NOW(3)) ON DUPLICATE KEY UPDATE primary_json=VALUES(primary_json),verify_json=VALUES(verify_json),created_at=NOW(3),source_run_id=VALUES(source_run_id),source_finding_id=VALUES(source_finding_id)",key,f.getScanRunId(),f.getId(),write(safeDecision(primary)),write(safeDecision(verify)));
    }
    /** 结果缓存与正式审核记录使用同样的脱敏和长度限制，不保存模型可能复述的完整凭据。 */
    private AiReviewDecision safeDecision(AiReviewDecision original){
        AiReviewDecision copy=read(write(original),AiReviewDecision.class);
        copy.setReason(limit(evidence.sanitize(copy.getReason()),4000));copy.setRecommendation(limit(evidence.sanitize(copy.getRecommendation()),4000));
        copy.setMissingEvidence((copy.getMissingEvidence()==null?List.<String>of():copy.getMissingEvidence()).stream().map(s->limit(evidence.sanitize(s),500)).collect(Collectors.toList()));return copy;
    }
    private String limit(String s,int max){return s==null?s:s.substring(0,Math.min(s.length(),max));}
    public void reused(Long jobId,FindingView f,Cached c) {db.update("INSERT INTO ai_review_reuse_log(job_id,finding_id,source_run_id,source_finding_id,created_at) VALUES(?,?,?,?,NOW(3))",jobId,f.getId(),c.sourceRunId,c.sourceFindingId);}
    public List<FindingView> selected(Plan p) {Set<Long> ids=new HashSet<>(p.findingIds);List<FindingView> rows=findings.findAuditInputsByRunId(p.scanRunId).stream().filter(f->ids.contains(f.getId())).collect(Collectors.toList());return findings.enrichSnippets(rows);}
    public Map<String,List<FindingView>> units(List<FindingView> rows) {
        Map<String,List<FindingView>> result=new LinkedHashMap<>();
        for(FindingView f:rows) {String dependency=DependencyFindingGrouping.groupKey(f);String key=dependency==null?"finding|"+f.getId():"dependency|"+dependency;result.computeIfAbsent(key,k->new ArrayList<>()).add(f);}return result;
    }
    public AiReviewEvidence unitEvidence(List<FindingView> unit) {
        AiReviewEvidence ev=evidence.build(unit.get(0));if(unit.size()==1)return ev;
        List<FindingView> sorted=new ArrayList<>(unit);sorted.sort(Comparator.comparing(f->Objects.toString(f.getRuleId(),"")));
        ev.setRuleId(sorted.stream().map(f->Objects.toString(f.getRuleId(),"unknown")).collect(Collectors.joining(",")));
        ev.setTitle(DependencyFindingGrouping.componentLabel(unit.get(0))+" 组件 "+unit.size()+" 个已知漏洞（合并复核）");
        ev.setDescription("同一依赖文件与组件版本的多个 CVE，请作为一个整改组统一复核："+sorted.stream().map(f->"\n- "+f.getRuleId()+"："+f.getTitle()).collect(Collectors.joining()));
        ev.setRecommendation("对该依赖组件统一升级到可修复上述 CVE 的安全版本，并完成兼容性验证。");return ev;
    }
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("审核预算序列化失败",e);}}
    private <T>T read(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("审核预算读取失败",e);}}
    private BusinessException bad(String s){return new BusinessException(HttpStatus.BAD_REQUEST,s);}
}
