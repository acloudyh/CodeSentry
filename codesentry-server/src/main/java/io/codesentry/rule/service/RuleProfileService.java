package io.codesentry.rule.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.rule.dto.RuleProfileView;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class RuleProfileService {

    public static final String COMMUNITY_GENERAL = "COMMUNITY_GENERAL";
    public static final String LOCAL_OWASP = "LOCAL_OWASP";
    public static final String COMPREHENSIVE = "COMPREHENSIVE";
    private static final List<String> LOCAL_RULES = Arrays.asList(
            "JSP 路径穿越 · CWE-22",
            "JSP 命令执行 · CWE-78",
            "JSP 开放重定向 · CWE-601",
            "JavaScript / TypeScript / React 跨窗口消息 · CWE-346",
            "JavaScript / TypeScript / React 浏览器输入跳转 · CWE-601",
            "JavaScript / TypeScript / React 敏感信息存储 · CWE-922",
            "JavaScript / TypeScript / React 明文 HTTP 请求 · CWE-319",
            "Vue 路由参数直接写入 v-html · CWE-79",
            "Vue 路由参数跳转 · CWE-601",
            "Vue 跨窗口消息 · CWE-346",
            "Vue 敏感信息存储 · CWE-922"
    );

    private final CodeSentryProperties properties;

    public RuleProfileService(CodeSentryProperties properties) {
        this.properties = properties;
    }

    public List<RuleProfileView> list() {
        return Arrays.asList(
                new RuleProfileView(LOCAL_OWASP, "本地 OWASP / CWE 基线",
                        "内置 Java、JSP/JSPX 与前端专项规则。JS/TS/JSX/TSX 使用语法规则及局部数据流检查，Vue 使用限定文本模式检查；不代表完整跨文件分析。",
                        Arrays.asList("OWASP Top 10", "CWE", "CERT Java", "Alibaba Java Coding Guidelines"),
                        Arrays.asList("Semgrep", "Gitleaks", "Trivy 漏洞/密钥", "SpotBugs/FindSecBugs", "Alibaba P3C"),
                        LOCAL_RULES, true),
                new RuleProfileView(COMMUNITY_GENERAL, "Semgrep 社区通用规则",
                        "使用 Semgrep auto 根据仓库语言匹配社区规则，首次使用可能需要访问规则仓库。",
                        Arrays.asList("Semgrep Registry", "OWASP", "CWE", "Alibaba Java Coding Guidelines"),
                        Arrays.asList("Semgrep", "Gitleaks", "Trivy 漏洞/密钥", "SpotBugs/FindSecBugs", "Alibaba P3C"),
                        Collections.emptyList(), false),
                new RuleProfileView(COMPREHENSIVE, "综合深度审计",
                        "同时执行本地基线和 Semgrep 社区规则，覆盖面更大，扫描时间也更长。",
                        Arrays.asList("OWASP Top 10", "CWE", "CERT", "Semgrep Registry", "Alibaba Java Coding Guidelines"),
                        Arrays.asList("Semgrep", "Gitleaks", "Trivy 漏洞/密钥", "SpotBugs/FindSecBugs", "Alibaba P3C"),
                        LOCAL_RULES, false)
        );
    }

    public void validate(String profile) {
        if (!LOCAL_OWASP.equals(profile) && !COMMUNITY_GENERAL.equals(profile)
                && !COMPREHENSIVE.equals(profile)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "不支持的扫描规则集");
        }
    }

    public List<String> semgrepConfigs(String profile) {
        validate(profile);
        if (COMMUNITY_GENERAL.equals(profile)) {
            return Collections.singletonList(properties.getScanners().getSemgrep().getConfig());
        }
        String local = materializeLocalRules("codesentry-owasp-cwe.yml").toString();
        String frontend = materializeLocalRules("codesentry-frontend.yml").toString();
        if (COMPREHENSIVE.equals(profile)) {
            return Arrays.asList(local, frontend, properties.getScanners().getSemgrep().getConfig());
        }
        return Arrays.asList(local, frontend);
    }

    private Path materializeLocalRules(String fileName) {
        Path directory = properties.getStorage().rootPath().resolve("rules");
        Path target = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            ClassPathResource resource = new ClassPathResource("rules/" + fileName);
            try (InputStream input = resource.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法准备本地扫描规则");
        }
    }
}
