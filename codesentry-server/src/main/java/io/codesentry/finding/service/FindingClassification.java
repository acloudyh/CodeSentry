package io.codesentry.finding.service;

import io.codesentry.finding.model.FindingView;
import java.util.*;
import java.util.stream.Collectors;

/** 按具体规则识别规范问题，扫描器名称不作为整个扫描器免审的依据。 */
public final class FindingClassification {
    public static final String VERSION="classification-v1";
    private static final Set<String> QUALITY_RULES=Set.of(
        "LowerCamelCaseVariableNamingRule","AbstractMethodOrInterfaceMethodMustUseJavadocRule","ClassMustHaveAuthorRule",
        "UndefineMagicConstantRule","AvoidCommentBehindStatementRule","CollectionInitShouldAssignCapacityRule",
        "ClassNamingShouldBeCamelRule","CommentsMustBeJavadocFormatRule","UpperEllRule","ConstantFieldShouldBeUpperCaseRule");
    private static volatile Map<String,String> overrides=Map.of();
    private static volatile String revision=VERSION;
    public static synchronized void configure(Map<String,String> rules){
        overrides=Map.copyOf(rules);
        try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(new TreeMap<>(rules).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));revision=VERSION+":"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static String version(){return revision;}
    private FindingClassification() {}
    public static String type(FindingView f) {
        String rule=f.getRuleId()==null?"":f.getRuleId();
        if(overrides.containsKey(rule))return overrides.get(rule);
        if (QUALITY_RULES.stream().anyMatch(r -> rule.equals("Alibaba-P3C."+r))) return "QUALITY";
        if (Set.of("SECURITY","SECRET","VULNERABILITY").contains(value(f.getCategory()).toUpperCase(Locale.ROOT))) return "SECURITY";
        return "AUDIT";
    }
    /** SQL 与 Java 使用同一规则名单，分类筛选在分页前执行，不加载全量正文。 */
    public static String sql(String alias) {
        String rules=QUALITY_RULES.stream().sorted().map(r -> "'Alibaba-P3C."+r+"'").collect(Collectors.joining(","));
        StringBuilder custom=new StringBuilder();
        new TreeMap<>(overrides).forEach((rule,type)->custom.append(" WHEN BINARY ").append(alias).append(".rule_id='").append(rule.replace("'","''")).append("' THEN '").append(type).append("'"));
        return "CASE "+custom+" WHEN BINARY "+alias+".rule_id IN ("+rules+") THEN 'QUALITY' WHEN UPPER("+alias+".category) IN ('SECURITY','SECRET','VULNERABILITY') THEN 'SECURITY' ELSE 'AUDIT' END";
    }
    /** 预审只决定处理路线，不把证据不足或样例线索伪装为误报。 */
    public static Map<String,String> precheck(FindingView f) {
        String type=type(f),route,reason,advice;
        if ("QUALITY".equals(type)) {
            route="RULE_ONLY";reason="编码规范由确定性规则检查，无需模型审核";
            String rule=value(f.getRuleId());
            advice=rule.contains("Naming") || rule.contains("Camel") ? "按规则调整命名，并通过编译、引用检查和规则复扫验证。"
                : rule.contains("Javadoc") || rule.contains("Comment") || rule.contains("Author") ? "按团队规范补充或调整注释；无需将源码提交模型。"
                : "按原规则建议调整代码，并通过规则复扫验证；此项不等同于安全漏洞。";
        } else if ("AUDIT".equals(type)) {
            route="CLASSIFY";reason="新规则尚未分类，需人工判断用途；不自动提交付费模型";advice="核对规则目的及代码上下文，再进行分类或人工复核。";
        } else if ("codesentry.javascript.eval".equals(f.getRuleId()) || value(f.getRuleId()).contains("eval-detected")) {
            route="MANUAL";reason="通用 eval 线索先人工核对实际使用场景，不自动重复提交模型";advice="核对输入来源和执行路径，线索保留，不因此判定为误报。";
        } else if ("VULNERABILITY".equalsIgnoreCase(f.getCategory())) {
            route="LOCAL_EVIDENCE";reason="依赖问题先核对组件版本与扫描漏洞库记录，无需模型重复解释 CVE";advice="核对锁文件或包清单、实际版本及修复版本；利用条件和可达性仍需结合业务核实。";
        } else if (f.getCodeSnippet()==null || f.getCodeSnippet().isBlank() || "[REDACTED]".equals(f.getCodeSnippet().trim())) {
            route="MANUAL";reason="当前缺少可核对的源码证据，先补充证据，不为无上下文的问题付费";advice="从来源快照补充代码或人工确认，不能据此排除风险。";
        } else if ("SECRET".equalsIgnoreCase(f.getCategory()) && f.getCodeSnippet().matches("(?is).*?(?:api[_-]?key|token|password|secret)\\s*[:=]\\s*[\"'](?:your[_-](?:api[_-]?key|token|password)|changeme|<[^>]+>|\\$\\{[^}]+\\})[\"'].*")) {
            route="MANUAL";reason="发现占位符线索，待人工核对；未尝试使用凭据，也未自动判定误报";advice="核对实际配置来源和部署替换机制。";
        } else {
            route="MODEL_CANDIDATE";reason="安全规则命中且有源码上下文，可按需提交双轮模型复核";advice="核对输入来源、调用链、权限与防护条件；模型建议不覆盖人工结论。";
        }
        return Map.of("route",route,"reason",reason,"recommendation",advice,"policyVersion",version());
    }
    private static String value(String s) { return s==null?"":s; }
}
