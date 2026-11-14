package io.codesentry.finding.service;

import com.fasterxml.jackson.databind.*;
import io.codesentry.finding.model.*;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.ScanRun;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 按精确组件版本与 CVE 联网核实修复候选及 Java 要求，并保存带时效的原始依据。 */
@Service
public class OnlineRemediationService {
  private final RemediationHttpClient remote;
  private final ObjectMapper json;
  private final FindingPersistenceService findings;
  private final ScanRunMapper runs;

  /** 注入受限外部查询、原问题读取和扫描依据持久化能力。 */
  public OnlineRemediationService(
      RemediationHttpClient remote,
      ObjectMapper json,
      FindingPersistenceService findings,
      ScanRunMapper runs) {
    this.remote = remote;
    this.json = json;
    this.findings = findings;
    this.runs = runs;
  }

  @org.springframework.beans.factory.annotation.Autowired
  private io.codesentry.config.CodeSentryProperties properties =
      new io.codesentry.config.CodeSentryProperties();

  @org.springframework.beans.factory.annotation.Autowired
  private io.codesentry.project.mapper.ScanProjectMapper assets;

  /** 按组件版本分组核实并更新进度，复用有效缓存；未核实项不得视为已修复。 */
  public void enrich(ScanRun run) {
    run.setRemediationStatus("RUNNING");
    run.setRemediationMessage("正在联网核实修复记录与 Java 要求");
    if (runs.updateRemediation(run) == 0) return;
    List<OnlineRemediationEvidence> evidence = new ArrayList<>();
    Map<String, JsonNode> responses = new LinkedHashMap<>();
    Budget budget = new Budget(properties.getRemediation());
    Map<String, Integer> javaRequirements = new LinkedHashMap<>();
    Map<String, OnlineRemediationEvidence> previous = new LinkedHashMap<>();
    // 仅复用本扫描 24 小时内的依据；使用最早缓存时间，反复补查不会无限延长有效期。
    String cacheCreatedAt = restoreSavedEvidence(run, responses, javaRequirements, previous);
    budget.responseBytes = responses.values().stream().mapToLong(v -> v.toString().length()).sum();
    Integer javaTarget =
        assets == null || run.getProjectId() == null
            ? null
            : Optional.ofNullable(assets.findById(run.getProjectId()))
                .map(p -> p.getTargetJavaVersion())
                .orElse(null);
    try {
      Map<String, List<FindingView>> groups =
          findings.findByRunId(run.getId()).stream()
              .filter(
                  f ->
                      "Trivy".equalsIgnoreCase(f.getScanner())
                          && "VULNERABILITY".equals(f.getCategory())
                          && !"FIXED".equals(f.getResultStatus())
                          && String.valueOf(f.getRuleId()).matches("CVE-[0-9]{4}-[0-9]+")
                          && coordinate(f).matches("[^:@\\s]+:[^:@\\s]+"))
              .collect(
                  Collectors.groupingBy(
                      f -> signature(f), LinkedHashMap::new, Collectors.toList()));
      int completedGroups = 0;
      for (List<FindingView> group : groups.values()) {
        io.codesentry.process.CancellableTasks.check();
        // 每个 CVE 都需有满足当前 Java 约束的已核实候选，才可跳过整个组件组。
        boolean reusable =
            group.stream()
                .allMatch(
                    f ->
                        previous.containsKey(signature(f) + "|" + f.getRuleId())
                            && "FOUND"
                                .equals(previous.get(signature(f) + "|" + f.getRuleId()).status)
                            && (javaTarget == null
                                || previous
                                    .get(signature(f) + "|" + f.getRuleId())
                                    .verifiedTargets
                                    .values()
                                    .stream()
                                    .anyMatch(j -> j <= javaTarget)));
        if (reusable)
          group.stream()
              .map(f -> previous.get(signature(f) + "|" + f.getRuleId()))
              .distinct()
              .forEach(evidence::add);
        else lookup(group, evidence, responses, javaRequirements, javaTarget, budget);
        run.setRemediationMessage(
            "正在联网核实，已处理 " + (++completedGroups) + " / " + groups.size() + " 个组件版本组");
        runs.updateRemediation(run);
      }
      long found = evidence.stream().filter(e -> "FOUND".equals(e.status)).count();
      long failed =
          evidence.stream()
              .filter(e -> Set.of("QUERY_FAILED", "LIMIT_REACHED").contains(e.status))
              .count();
      run.setRemediationStatus(failed == 0 ? "COMPLETED" : "PARTIAL");
      run.setRemediationMessage(
          "联网核实 "
              + evidence.size()
              + " 项，取得可核实版本 "
              + found
              + " 项，"
              + (evidence.size() - found)
              + " 项仍需结合本地资料或人工核实。"
              + (failed == 0 ? "" : "部分查询失败或达到时限。"));
    } catch (Exception error) {
      if (Thread.currentThread().isInterrupted()
          || error instanceof java.util.concurrent.CancellationException)
        throw new java.util.concurrent.CancellationException();
      run.setRemediationStatus("FAILED");
      run.setRemediationMessage("联网核实未完成，报告沿用本地资料；未核实项不视为已修复。");
    }
    try {
      run.setRemediationEvidence(
          json.writeValueAsString(
              Map.of(
                  "schema",
                  1,
                  "checkedAt",
                  Instant.now().toString(),
                  "entries",
                  evidence,
                  "responses",
                  responses,
                  "javaRequirements",
                  javaRequirements,
                  "cacheCreatedAt",
                  cacheCreatedAt)));
    } catch (Exception e) {
      throw new IllegalStateException("联网依据保存失败", e);
    }
    io.codesentry.process.CancellableTasks.check();
    runs.updateRemediation(run);
  }

  /** 匹配本组 CVE 的上游记录，并对同发布线的候选版本逐一核实修复覆盖和 Java 要求。 */
  private void lookup(
      List<FindingView> group,
      List<OnlineRemediationEvidence> all,
      Map<String, JsonNode> responses,
      Map<String, Integer> javaRequirements,
      Integer javaTarget,
      Budget budget) {
    String pkg = coordinate(group.get(0)), installed = installed(group.get(0));
    Map<String, OnlineRemediationEvidence> records = new LinkedHashMap<>();
    for (FindingView f : group)
      records.computeIfAbsent(
          f.getRuleId(),
          cve -> {
            OnlineRemediationEvidence e = new OnlineRemediationEvidence();
            e.coordinate = pkg;
            e.installedVersion = installed;
            e.cve = cve;
            e.checkedAt = Instant.now().toString();
            e.status = "NOT_FOUND";
            e.sources.add(
                "https://osv.dev/list?q="
                    + java.net.URLEncoder.encode(
                        pkg + " " + cve, java.nio.charset.StandardCharsets.UTF_8));
            all.add(e);
            return e;
          });
    boolean initialResolved = false;
    try {
      JsonNode initial = query(pkg, installed, responses, budget);
      initialResolved = true;
      Map<String, List<JsonNode>> matched = new LinkedHashMap<>();
      for (OnlineRemediationEvidence e : records.values()) {
        List<JsonNode> advisories = matching(initial, pkg, e.cve);
        matched.put(e.cve, advisories);
        if (advisories.isEmpty()) continue;
        Set<String> fixed = new LinkedHashSet<>();
        for (JsonNode advisory : advisories) {
          e.sources.add("https://osv.dev/vulnerability/" + advisory.path("id").asText());
          for (JsonNode reference : advisory.path("references")) {
            String url = reference.path("url").asText();
            if (url.startsWith("https://") && e.sources.size() < 15) e.sources.add(url);
          }
          for (JsonNode affected : advisory.path("affected"))
            if (isPackage(affected, pkg))
              for (JsonNode range : affected.path("ranges"))
                if (Set.of("ECOSYSTEM", "SEMVER").contains(range.path("type").asText()))
                  for (JsonNode event : range.path("events")) {
                    String version = event.path("fixed").asText();
                    if (stable(version)) fixed.add(version);
                  }
        }
        e.fixedVersions.addAll(fixed);
        e.status = fixed.isEmpty() ? "NO_FIXED_VERSION" : "UNVERIFIED";
      }
      Set<String> candidates =
          new LinkedHashSet<>(UnifiedDependencyRemediation.localCandidates(group));
      records.values().forEach(e -> candidates.addAll(e.fixedVersions));
      candidates.removeIf(
          v -> !stable(v) || UnifiedDependencyRemediation.compareVersions(v, installed) < 0);
      // Prefer one latest candidate per release line before checking older candidates.
      List<String> ordered =
          candidates.stream()
              .sorted((a, b) -> UnifiedDependencyRemediation.compareVersions(b, a))
              .collect(Collectors.toList());
      Map<String, String> branches = new LinkedHashMap<>();
      ordered.forEach(
          v ->
              branches.putIfAbsent(
                  Arrays.stream(v.split("\\.")).limit(2).collect(Collectors.joining(".")), v));
      LinkedHashSet<String> selected = new LinkedHashSet<>(branches.values());
      selected.addAll(ordered);
      int checked = 0;
      for (String target : selected) {
        // 兼容的候选已覆盖组内全部 CVE 时停止；Java 未确认时保留更多候选供人工选择。
        if (javaTarget != null
            && records.values().stream()
                .allMatch(e -> e.verifiedTargets.values().stream().anyMatch(j -> j <= javaTarget)))
          break;
        if (++checked > 8) break;
        if (matched.values().stream().allMatch(List::isEmpty)) break;
        JsonNode targetResult = query(pkg, target, responses, budget);
        Integer minimumJava = UnifiedDependencyRemediation.minimumJava(pkg, target);
        String javaKey = pkg + "@" + target;
        if (minimumJava == null) minimumJava = javaRequirements.get(javaKey);
        if (minimumJava == null) {
          budget.reserve();
          try {
            minimumJava = remote.minimumJava(pkg, target);
          } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            io.codesentry.process.CancellableTasks.check();
            continue;
          }
        }
        if (minimumJava == null) continue;
        javaRequirements.put(javaKey, minimumJava);
        for (OnlineRemediationEvidence e : records.values()) {
          if (e.fixedVersions.isEmpty() || !matching(targetResult, pkg, e.cve).isEmpty()) continue;
          // 修复依据必须同时具备：原版本命中、同发布线的 fixed 事件、精确候选版本不再命中。
          // 查询不到漏洞或来源不识别组件，不能单独证明候选版本安全。
          boolean eligible =
              e.fixedVersions.stream()
                  .anyMatch(
                      v ->
                          UnifiedDependencyRemediation.sameReleaseLine(target, v)
                              && UnifiedDependencyRemediation.compareVersions(target, v) >= 0);
          if (eligible) {
            e.verifiedTargets.put(target, minimumJava);
            e.status = "FOUND";
            e.sources.add(UnifiedDependencyRemediation.javaSource(pkg, target));
          }
        }
      }
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      io.codesentry.process.CancellableTasks.check();
      for (OnlineRemediationEvidence e : records.values()) {
        if (!e.verifiedTargets.isEmpty()
            || (initialResolved && Set.of("NO_FIXED_VERSION", "NOT_FOUND").contains(e.status)))
          continue;
        e.status = error instanceof LimitReached ? "LIMIT_REACHED" : "QUERY_FAILED";
        e.message =
            error instanceof LimitReached ? error.getMessage() : "远端服务暂不可用，可补充联网核实；本地漏洞库记录保留。";
      }
    }
  }

  /** 查询精确组件版本，校验响应完整性并计入时间、请求和容量预算。 */
  private JsonNode query(String pkg, String version, Map<String, JsonNode> responses, Budget budget)
      throws Exception {
    String key = pkg + "@" + version;
    if (responses.containsKey(key)) return responses.get(key);
    budget.reserve();
    JsonNode answer = remote.query(pkg, version);
    // An empty object is OSV's documented no-vulnerabilities response; error payloads are not.
    if (answer == null
        || !answer.isObject()
        || (!answer.isEmpty() && !answer.has("vulns") && !answer.has("next_page_token"))
        || answer.has("error")
        || answer.has("code")
        || (answer.has("vulns") && !answer.path("vulns").isArray()))
      throw new IllegalStateException("无效的漏洞查询响应");
    if (!answer.path("next_page_token").asText().isBlank())
      throw new IllegalStateException("漏洞查询未完整返回，覆盖尚未核实");
    budget.responseBytes += answer.toString().length();
    if (budget.responseBytes > budget.maxResponseBytes)
      throw new LimitReached("本次累计响应数据达到配置上限，剩余项可继续补查。");
    responses.put(key, answer);
    return answer;
  }

  static List<JsonNode> matching(JsonNode response, String pkg, String cve) {
    List<JsonNode> result = new ArrayList<>();
    for (JsonNode advisory : response.path("vulns")) {
      if (advisory.hasNonNull("withdrawn")) continue;
      boolean id = cve.equals(advisory.path("id").asText());
      for (JsonNode alias : advisory.path("aliases")) id |= cve.equals(alias.asText());
      if (!id) continue;
      for (JsonNode affected : advisory.path("affected"))
        if (isPackage(affected, pkg)) {
          result.add(advisory);
          break;
        }
    }
    return result;
  }

  /** 要求来源生态与 Maven 坐标完全匹配，防止同名组件串用依据。 */
  private static boolean isPackage(JsonNode affected, String pkg) {
    return "Maven".equals(affected.path("package").path("ecosystem").asText())
        && pkg.equals(affected.path("package").path("name").asText());
  }

  /** 仅接纳支持比较的稳定版本格式，排除无法可靠排序的候选。 */
  private static boolean stable(String value) {
    return value.matches("[0-9]+(?:\\.[0-9]+)*(?:(?:\\.Final|\\.RELEASE)|-(?:jre|android))?");
  }

  /** 提取扫描片段首行的组件坐标与安装版本签名。 */
  public static String signature(FindingView f) {
    return f.getCodeSnippet() == null ? "" : f.getCodeSnippet().split("\\R", 2)[0].trim();
  }

  /** 从组件签名解析 Maven 坐标，不包含安装版本。 */
  public static String coordinate(FindingView f) {
    String s = signature(f);
    int at = s.lastIndexOf('@');
    return at < 0 ? "" : s.substring(0, at);
  }

  /** 从组件签名解析本次扫描的实际安装版本。 */
  private static String installed(FindingView f) {
    String s = signature(f);
    int at = s.lastIndexOf('@');
    return at < 0 ? "" : s.substring(at + 1);
  }

  /** 将保存的联网证据按组件版本和 CVE 关联回问题视图；报告导出无需重新联网。 */
  public static void attach(ScanRun run, List<FindingView> findings) {
    if (run.getRemediationEvidence() == null) return;
    try {
      ObjectMapper mapper = new ObjectMapper();
      Map<String, OnlineRemediationEvidence> entries = new LinkedHashMap<>();
      for (JsonNode item : mapper.readTree(run.getRemediationEvidence()).path("entries")) {
        OnlineRemediationEvidence e = mapper.treeToValue(item, OnlineRemediationEvidence.class);
        entries.put(e.coordinate + "@" + e.installedVersion + "|" + e.cve, e);
      }
      for (FindingView f : findings)
        f.setOnlineRemediation(entries.get(signature(f) + "|" + f.getRuleId()));
    } catch (Exception error) {
      throw new IllegalStateException("本次扫描的联网依据读取失败", error);
    }
  }

  /** Resume only this scan's dated, successful evidence; expired responses are queried again. */
  private String restoreSavedEvidence(
      ScanRun run,
      Map<String, JsonNode> responses,
      Map<String, Integer> javaRequirements,
      Map<String, OnlineRemediationEvidence> previous) {
    String now = Instant.now().toString();
    if (run.getRemediationEvidence() == null) return now;
    try {
      JsonNode saved = json.readTree(run.getRemediationEvidence());
      String origin =
          saved.hasNonNull("cacheCreatedAt")
              ? saved.path("cacheCreatedAt").asText()
              : saved.path("checkedAt").asText();
      long age = Duration.between(Instant.parse(origin), Instant.now()).getSeconds();
      if (age < 0 || age > 86400) return now;
      saved
          .path("responses")
          .fields()
          .forEachRemaining(e -> responses.put(e.getKey(), e.getValue()));
      saved
          .path("javaRequirements")
          .fields()
          .forEachRemaining(
              e -> {
                if (e.getValue().isInt() && e.getValue().asInt() > 0)
                  javaRequirements.put(e.getKey(), e.getValue().asInt());
              });
      for (JsonNode node : saved.path("entries")) {
        OnlineRemediationEvidence e = json.treeToValue(node, OnlineRemediationEvidence.class);
        previous.put(e.coordinate + "@" + e.installedVersion + "|" + e.cve, e);
        e.verifiedTargets.forEach(
            (version, java) -> {
              if (java != null && java > 0)
                javaRequirements.put(e.coordinate + "@" + version, java);
            });
      }
      return origin;
    } catch (Exception ignored) {
      responses.clear();
      javaRequirements.clear();
      previous.clear();
      return now;
    }
  }

  private static class LimitReached extends RuntimeException {
    LimitReached(String reason) {
      super(reason);
    }
  }

  private static class Budget {
    final long deadline, maxResponseBytes;
    final int maxQueries;
    int queries;
    long responseBytes;

    Budget(io.codesentry.config.CodeSentryProperties.Remediation settings) {
      deadline =
          System.nanoTime()
              + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                  Math.max(1, Math.min(1800, settings.getTimeoutSeconds())));
      maxQueries = Math.max(1, Math.min(10000, settings.getMaxQueries()));
      maxResponseBytes =
          Math.max(1, Math.min(128, settings.getMaxResponseMegabytes())) * 1024L * 1024;
    }

    void reserve() {
      io.codesentry.process.CancellableTasks.check();
      if (System.nanoTime() > deadline) throw new LimitReached("本次核实时长达到配置上限，剩余项可继续补查。");
      if (++queries > maxQueries) throw new LimitReached("本次远端请求数达到配置上限，剩余项可继续补查。");
    }
  }
}
