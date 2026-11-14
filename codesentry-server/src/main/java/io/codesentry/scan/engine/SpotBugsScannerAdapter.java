package io.codesentry.scan.engine;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandExecutor;
import io.codesentry.process.CommandResult;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Component
public class SpotBugsScannerAdapter implements ScannerAdapter {

  private final CommandExecutor commandExecutor;
  private final CodeSentryProperties properties;

  public SpotBugsScannerAdapter(CommandExecutor commandExecutor, CodeSentryProperties properties) {
    this.commandExecutor = commandExecutor;
    this.properties = properties;
  }

  @Override
  public String name() {
    return "SpotBugs-FindSecBugs";
  }

  @Override
  public boolean enabled() {
    return properties.getScanners().getSpotbugs().isEnabled();
  }

  @Override
  public String reportExtension() {
    return "xml";
  }

  @Override
  public boolean available() {
    try {
      return commandExecutor
          .execute(
              null,
              List.of(properties.getScanners().getSpotbugs().getExecutable(), "-version"),
              Duration.ofSeconds(15))
          .isSuccess();
    } catch (RuntimeException exception) {
      return false;
    }
  }

  @Override
  public List<NormalizedFinding> scan(ScanContext context) {
    boolean uploaded =
        context.getWorkspaceMode() == io.codesentry.scan.model.WorkspaceMode.JAR_UPLOAD;
    List<Path> archives = findArchives(context.getWorkspace());
    List<Path> dependencies = List.of();
    if (uploaded) {
      if (archives.size() != 1)
        throw new BusinessException(HttpStatus.BAD_REQUEST, "上传扫描应包含一个 JAR 文件");
      io.codesentry.scan.archive.JarArchive.Layout layout =
          io.codesentry.scan.archive.JarArchive.prepare(
              archives.get(0),
              context.getOutputDirectory().resolve("bytecode-" + context.getLabel()));
      archives = List.of(layout.getTarget());
      dependencies = layout.getDependencies();
    }
    if (archives.isEmpty()) {
      return List.of();
    }

    Path report =
        context.getOutputDirectory().resolve("spotbugs-findsecbugs-" + context.getLabel() + ".xml");
    List<String> command = new ArrayList<>();
    command.add(properties.getScanners().getSpotbugs().getExecutable());
    command.add("-maxHeap");
    command.add(
        String.valueOf(
            Math.max(256, properties.getScanners().getSpotbugs().getMaxHeapMegabytes())));
    command.add("-textui");
    command.add("-xml:withMessages");
    command.add("-output");
    command.add(report.toString());
    command.add("-effort:default");
    command.add("-medium");

    String plugin = properties.getScanners().getSpotbugs().getConfig();
    if (uploaded && (plugin == null || plugin.isBlank())) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "JAR 安全扫描必须配置 FindSecBugs 插件");
    }
    if (plugin != null && !plugin.isBlank()) {
      Path pluginPath = Path.of(plugin).toAbsolutePath().normalize();
      if (!Files.isRegularFile(pluginPath)) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "FindSecBugs 插件文件不存在：" + pluginPath);
      }
      command.add("-pluginList");
      command.add(pluginPath.toString());
    }

    String include = properties.getScanners().getSpotbugs().getInclude();
    if (!uploaded && include != null && !include.isBlank()) {
      command.add("-onlyAnalyze");
      command.add(include);
    }
    Path auxiliaryClasspath = null;
    if (uploaded) {
      command.add("-bugCategories");
      command.add("SECURITY");
      if (!dependencies.isEmpty()) {
        Path classpath =
            context.getOutputDirectory().resolve("spotbugs-aux-" + context.getLabel() + ".txt");
        try {
          Files.write(
              classpath,
              dependencies.stream()
                  .map(path -> path.toAbsolutePath().toString())
                  .collect(Collectors.toList()));
        } catch (java.io.IOException exception) {
          throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法准备 JAR 依赖类路径");
        }
        auxiliaryClasspath = classpath;
      }
    }
    Path project = context.getOutputDirectory().resolve("spotbugs-" + context.getLabel() + ".fbp");
    writeProject(project, archives);
    command.add("-project");
    command.add(project.toString());
    // Loading a project replaces SpotBugs' project classpath. Add auxiliaries afterward.
    if (auxiliaryClasspath != null) {
      command.add("-auxclasspathFromFile");
      command.add(auxiliaryClasspath.toString());
    }

    int timeoutSeconds = Math.max(30, properties.getScanners().getSpotbugs().getTimeoutSeconds());
    CommandResult result =
        commandExecutor.execute(
            context.getWorkspace(), command, Duration.ofSeconds(timeoutSeconds));
    // Minimal launchers invoke the Java main class directly and do not understand
    // the distribution shell script's heap switch. Retry only that specific incompatibility.
    if (uploaded
        && !result.isSuccess()
        && result.getOutput() != null
        && result.getOutput().contains("Unknown option: -maxHeap")) {
      command.subList(1, 3).clear();
      result =
          commandExecutor.execute(
              context.getWorkspace(), command, Duration.ofSeconds(timeoutSeconds));
    }
    if (!result.isSuccess() && !(result.getExitCode() == 1 && isUsableReport(report))) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "SpotBugs/FindSecBugs 扫描执行失败（退出码 " + result.getExitCode() + "）");
    }
    return parseReport(report);
  }

  private boolean isUsableReport(Path report) {
    if (!Files.isRegularFile(report)) return false;
    try {
      Document document = readReport(report);
      if (document.getDocumentElement() == null
          || !"BugCollection".equals(document.getDocumentElement().getTagName())
          || document.getElementsByTagName("FindBugsSummary").getLength() == 0) {
        return false;
      }
      NodeList errors = document.getElementsByTagName("Errors");
      if (errors.getLength() == 0) return true;
      String errorCount = ((Element) errors.item(0)).getAttribute("errors");
      return errorCount.isBlank() || "0".equals(errorCount);
    } catch (Exception exception) {
      return false;
    }
  }

  void writeProject(Path target, List<Path> archives) {
    try {
      Files.createDirectories(target.getParent());
      XMLOutputFactory outputFactory = XMLOutputFactory.newFactory();
      try (OutputStream output = Files.newOutputStream(target)) {
        XMLStreamWriter writer = outputFactory.createXMLStreamWriter(output, "UTF-8");
        writer.writeStartDocument("UTF-8", "1.0");
        writer.writeCharacters("\n");
        writer.writeStartElement("Project");
        writer.writeAttribute("projectName", "CodeSentry bytecode scan");
        writer.writeCharacters("\n");
        for (Path archive : archives) {
          writer.writeStartElement("Jar");
          writer.writeCharacters(archive.toAbsolutePath().normalize().toString());
          writer.writeEndElement();
          writer.writeCharacters("\n");
        }
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.close();
      }
    } catch (Exception exception) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建 SpotBugs 项目文件");
    }
  }

  List<Path> findArchives(Path workspace) {
    try (Stream<Path> paths = Files.walk(workspace)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(this::isArchive)
          .sorted(Comparator.comparing(Path::toString))
          .collect(Collectors.toList());
    } catch (Exception exception) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "无法枚举待扫描的 JAR 文件");
    }
  }

  private boolean isArchive(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".jar") || name.matches(".*\\.jar\\d+$");
  }

  List<NormalizedFinding> parseReport(Path report) {
    if (!Files.isRegularFile(report)) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "SpotBugs 未生成预期的 XML 报告");
    }
    try {
      Document document = readReport(report);
      NodeList bugs = document.getElementsByTagName("BugInstance");
      List<NormalizedFinding> findings = new ArrayList<>();
      for (int index = 0; index < bugs.getLength(); index++) {
        findings.add(toFinding((Element) bugs.item(index)));
      }
      return findings;
    } catch (Exception exception) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "SpotBugs XML 报告解析失败");
    }
  }

  private Document readReport(Path report) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder().parse(report.toFile());
  }

  private NormalizedFinding toFinding(Element bug) {
    String type = bug.getAttribute("type");
    String category = bug.getAttribute("category");
    String priority = bug.getAttribute("priority");
    String rank = bug.getAttribute("rank");
    Element source = firstElement(bug, "SourceLine");
    Element clazz = firstElement(bug, "Class");
    Element method = firstElement(bug, "Method");

    String className = clazz == null ? null : clazz.getAttribute("classname");
    String methodName = method == null ? null : method.getAttribute("name");
    String sourcePath = source == null ? null : source.getAttribute("sourcepath");
    String title = childText(bug, "ShortMessage");
    String description = childText(bug, "LongMessage");

    NormalizedFinding finding = new NormalizedFinding();
    finding.setScanner(name());
    finding.setRuleId(type);
    finding.setCategory(category == null || category.isBlank() ? "BYTECODE" : category);
    finding.setSeverity(severity(priority, rank));
    finding.setTitle(title == null || title.isBlank() ? type : title);
    finding.setDescription(
        description == null || description.isBlank() ? finding.getTitle() : description);
    finding.setRecommendation("检查该字节码问题的数据来源和调用链；安全类问题优先升级依赖或修复自研实现。");
    String cwe = bug.getAttribute("cweid");
    if (cwe != null && !cwe.isBlank()) finding.setCwe("CWE-" + cwe);
    finding.setFilePath(
        sourcePath == null || sourcePath.isBlank() ? defaultClassPath(className) : sourcePath);
    finding.setStartLine(integerAttribute(source, "start"));
    finding.setEndLine(integerAttribute(source, "end"));
    finding.setCodeSnippet(
        (className == null ? "" : className)
            + (methodName == null || methodName.isBlank() ? "" : "#" + methodName));
    return finding;
  }

  private FindingSeverity severity(String priority, String rank) {
    int rankValue = parseInt(rank, 20);
    int priorityValue = parseInt(priority, 3);
    if (rankValue <= 4) return FindingSeverity.CRITICAL;
    if (priorityValue == 1 || rankValue <= 9) return FindingSeverity.HIGH;
    if (priorityValue == 2 || rankValue <= 14) return FindingSeverity.MEDIUM;
    return FindingSeverity.LOW;
  }

  private Element firstElement(Element parent, String name) {
    NodeList values = parent.getElementsByTagName(name);
    return values.getLength() == 0 ? null : (Element) values.item(0);
  }

  private String childText(Element parent, String name) {
    NodeList values = parent.getChildNodes();
    for (int index = 0; index < values.getLength(); index++) {
      Node node = values.item(index);
      if (node instanceof Element && name.equals(node.getNodeName())) {
        return node.getTextContent();
      }
    }
    return null;
  }

  private Integer integerAttribute(Element element, String name) {
    if (element == null) return null;
    String value = element.getAttribute(name);
    if (value == null || value.isBlank()) return null;
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private int parseInt(String value, int fallback) {
    try {
      return Integer.parseInt(value);
    } catch (Exception exception) {
      return fallback;
    }
  }

  private String defaultClassPath(String className) {
    return className == null || className.isBlank()
        ? "[compiled-bytecode]"
        : className.replace('.', '/') + ".class";
  }
}
