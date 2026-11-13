package io.codesentry.scan.engine;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Component
public class AlibabaP3cScannerAdapter implements ScannerAdapter {

    private static final String MAIN_CLASS = "net.sourceforge.pmd.PMD";
    // 命名风格、Javadoc/作者等注释检查不属于本产品的安全扫描范围，直接不加载规则集。
    private static final String RULESETS = String.join(",",
            "rulesets/java/ali-concurrent.xml",
            "rulesets/java/ali-constant.xml",
            "rulesets/java/ali-exception.xml",
            "rulesets/java/ali-flowcontrol.xml",
            "rulesets/java/ali-oop.xml",
            "rulesets/java/ali-orm.xml",
            "rulesets/java/ali-other.xml",
            "rulesets/java/ali-set.xml");

    private final CommandExecutor commandExecutor;
    private final CodeSentryProperties properties;

    public AlibabaP3cScannerAdapter(CommandExecutor commandExecutor, CodeSentryProperties properties) {
        this.commandExecutor = commandExecutor;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "Alibaba-P3C";
    }

    @Override
    public boolean enabled() {
        return properties.getScanners().getP3c().isEnabled();
    }

    @Override
    public String reportExtension() {
        return "xml";
    }

    @Override
    public boolean available() {
        Path jar = configuredJar();
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try {
            return commandExecutor.execute(null,
                    List.of(properties.getScanners().getP3c().getExecutable(), "-version"),
                    Duration.ofSeconds(15)).isSuccess();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public List<NormalizedFinding> scan(ScanContext context) {
        if (!containsJavaSource(context.getWorkspace())) {
            return List.of();
        }

        Path report = context.getOutputDirectory().resolve("alibaba-p3c-" + context.getLabel() + ".xml");
        try {
            Files.createDirectories(context.getOutputDirectory());
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建阿里巴巴 P3C 报告目录");
        }

        List<String> command = new ArrayList<>();
        command.add(properties.getScanners().getP3c().getExecutable());
        command.add("-cp");
        command.add(configuredJar().toString());
        command.add(MAIN_CLASS);
        command.add("-d");
        command.add(context.getWorkspace().toString());
        command.add("-R");
        command.add(RULESETS);
        command.add("-f");
        command.add("xml");
        command.add("-r");
        command.add(report.toString());
        command.add("-language");
        command.add("java");
        command.add("-version");
        command.add(configuredJavaVersion());
        command.add("-failOnViolation");
        command.add("false");
        command.add("-no-cache");

        int timeoutSeconds = Math.max(30, properties.getScanners().getP3c().getTimeoutSeconds());
        CommandResult result = commandExecutor.execute(context.getWorkspace(), command,
                Duration.ofSeconds(timeoutSeconds));
        if (!result.isSuccess()) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "阿里巴巴 P3C 扫描执行失败（退出码 " + result.getExitCode() + "）");
        }
        return parseReport(report, context.getWorkspace());
    }

    List<NormalizedFinding> parseReport(Path report, Path workspace) {
        if (!Files.isRegularFile(report)) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "阿里巴巴 P3C 未生成预期的 XML 报告");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(report.toFile());
            List<NormalizedFinding> findings = new ArrayList<>();
            NodeList files = document.getElementsByTagNameNS("*", "file");
            for (int fileIndex = 0; fileIndex < files.getLength(); fileIndex++) {
                Element file = (Element) files.item(fileIndex);
                String filePath = relativePath(workspace, file.getAttribute("name"));
                NodeList children = file.getChildNodes();
                for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                    Node child = children.item(childIndex);
                    if (!(child instanceof Element) || !"violation".equals(child.getLocalName())) {
                        continue;
                    }
                    Element violation = (Element) child;
                    int startLine = integer(violation.getAttribute("beginline"));
                    int endLine = integer(violation.getAttribute("endline"));
                    String rule = violation.getAttribute("rule");
                    String message = violation.getTextContent() == null
                            ? rule : violation.getTextContent().trim().replaceAll("\\s+", " ");

                    NormalizedFinding finding = new NormalizedFinding();
                    finding.setScanner(name());
                    finding.setRuleId("Alibaba-P3C." + rule);
                    finding.setCategory("CODE_STANDARD");
                    finding.setSeverity(severity(integer(violation.getAttribute("priority"))));
                    finding.setTitle(message.isBlank() ? rule : message);
                    finding.setDescription("阿里巴巴 Java 开发规范命中：" + violation.getAttribute("ruleset"));
                    finding.setRecommendation("按阿里巴巴 Java 开发规范调整代码，并结合项目兼容性进行人工确认。");
                    finding.setFilePath(filePath);
                    finding.setStartLine(startLine);
                    finding.setEndLine(endLine);
                    finding.setCodeSnippet(SourceSnippetExtractor.extract(workspace, filePath,
                            startLine, endLine, null));
                    findings.add(finding);
                }
            }
            return findings;
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "阿里巴巴 P3C XML 报告解析失败");
        }
    }

    private Path configuredJar() {
        String configured = properties.getScanners().getP3c().getConfig();
        return Path.of(configured == null ? "" : configured).toAbsolutePath().normalize();
    }

    private String configuredJavaVersion() {
        String configured = properties.getScanners().getP3c().getInclude();
        return configured == null || configured.isBlank() ? "1.8" : configured.trim();
    }

    private boolean containsJavaSource(Path workspace) {
        try (Stream<Path> paths = Files.walk(workspace)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java"));
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法检查 Java 源文件");
        }
    }

    private String relativePath(Path workspace, String value) {
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            Path root = workspace.toAbsolutePath().normalize();
            return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : value;
        } catch (RuntimeException exception) {
            return value;
        }
    }

    private int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private FindingSeverity severity(int priority) {
        if (priority <= 1) return FindingSeverity.CRITICAL;
        if (priority == 2) return FindingSeverity.HIGH;
        if (priority == 3) return FindingSeverity.MEDIUM;
        if (priority == 4) return FindingSeverity.LOW;
        return FindingSeverity.INFO;
    }
}
