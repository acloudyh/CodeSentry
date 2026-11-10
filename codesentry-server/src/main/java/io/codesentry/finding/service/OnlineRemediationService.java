package io.codesentry.finding.service;

import com.fasterxml.jackson.databind.*;
import io.codesentry.finding.model.*;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.ScanRun;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OnlineRemediationService {
    private final RemediationHttpClient remote;
    private final ObjectMapper json;
    private final FindingPersistenceService findings;
    private final ScanRunMapper runs;
    public OnlineRemediationService(RemediationHttpClient remote, ObjectMapper json,
                                    FindingPersistenceService findings, ScanRunMapper runs) {
        this.remote = remote; this.json = json; this.findings = findings; this.runs = runs;
    }

    public void enrich(ScanRun run) {
        run.setRemediationStatus("RUNNING");
        run.setRemediationMessage("正在联网核实修复记录与 Java 要求");
        if (runs.updateRemediation(run) == 0) return;
        List<OnlineRemediationEvidence> evidence = new ArrayList<>();
        Map<String, JsonNode> responses = new LinkedHashMap<>();
        Budget budget = new Budget();
        try {
            Map<String, List<FindingView>> groups = findings.findByRunId(run.getId()).stream()
                    .filter(f -> "Trivy".equalsIgnoreCase(f.getScanner()) && "VULNERABILITY".equals(f.getCategory())
                            && !"FIXED".equals(f.getResultStatus()) && String.valueOf(f.getRuleId()).matches("CVE-[0-9]{4}-[0-9]+")
                            && coordinate(f).matches("[^:@\\s]+:[^:@\\s]+"))
                    .collect(Collectors.groupingBy(f -> signature(f), LinkedHashMap::new, Collectors.toList()));
            for (List<FindingView> group : groups.values()) {
                io.codesentry.process.CancellableTasks.check();
                lookup(group, evidence, responses, budget);
            }
            long found = evidence.stream().filter(e -> "FOUND".equals(e.status)).count();
            long failed = evidence.stream().filter(e -> Set.of("QUERY_FAILED", "LIMIT_REACHED").contains(e.status)).count();
            run.setRemediationStatus(failed == 0 ? "COMPLETED" : "PARTIAL");
            run.setRemediationMessage("联网核实 " + evidence.size() + " 项，取得可核实版本 " + found + " 项，"
                    + (evidence.size() - found) + " 项仍需结合本地资料或人工核实。" + (failed == 0 ? "" : "部分查询失败或达到时限。"));
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted() || error instanceof java.util.concurrent.CancellationException)
                throw new java.util.concurrent.CancellationException();
            run.setRemediationStatus("FAILED");
            run.setRemediationMessage("联网核实未完成，报告沿用本地资料；未核实项不视为已修复。");
        }
        try {
            run.setRemediationEvidence(json.writeValueAsString(Map.of("schema", 1,
                    "checkedAt", Instant.now().toString(), "entries", evidence, "responses", responses)));
        } catch (Exception e) { throw new IllegalStateException("联网依据保存失败", e); }
        io.codesentry.process.CancellableTasks.check();
        runs.updateRemediation(run);
    }

    private void lookup(List<FindingView> group, List<OnlineRemediationEvidence> all,
                        Map<String, JsonNode> responses, Budget budget) {
        String pkg = coordinate(group.get(0)), installed = installed(group.get(0));
        Map<String, OnlineRemediationEvidence> records = new LinkedHashMap<>();
        for (FindingView f : group) records.computeIfAbsent(f.getRuleId(), cve -> {
            OnlineRemediationEvidence e = new OnlineRemediationEvidence();
            e.coordinate = pkg; e.installedVersion = installed; e.cve = cve; e.checkedAt = Instant.now().toString(); e.status = "NOT_FOUND";
            e.sources.add("https://osv.dev/list?q=" + java.net.URLEncoder.encode(pkg + " " + cve, java.nio.charset.StandardCharsets.UTF_8));
            all.add(e); return e;
        });
        try {
            JsonNode initial = query(pkg, installed, responses, budget);
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
                    for (JsonNode affected : advisory.path("affected")) if (isPackage(affected, pkg))
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
            Set<String> candidates = new LinkedHashSet<>(UnifiedDependencyRemediation.localCandidates(group));
            records.values().forEach(e -> candidates.addAll(e.fixedVersions));
            candidates.removeIf(v -> !stable(v) || UnifiedDependencyRemediation.compareVersions(v, installed) < 0);
            // Prefer one latest candidate per release line before checking older candidates.
            List<String> ordered = candidates.stream().sorted((a,b) -> UnifiedDependencyRemediation.compareVersions(b,a)).collect(Collectors.toList());
            Map<String, String> branches = new LinkedHashMap<>();
            ordered.forEach(v -> branches.putIfAbsent(Arrays.stream(v.split("\\.")).limit(2).collect(Collectors.joining(".")), v));
            LinkedHashSet<String> selected = new LinkedHashSet<>(branches.values()); selected.addAll(ordered);
            int checked = 0;
            for (String target : selected) {
                if (++checked > 8) break;
                if (matched.values().stream().allMatch(List::isEmpty)) break;
                JsonNode targetResult = query(pkg, target, responses, budget);
                Integer minimumJava = UnifiedDependencyRemediation.minimumJava(pkg, target);
                if (minimumJava == null) {
                    budget.check();
                    try { minimumJava = remote.minimumJava(pkg, target); }
                    catch (Exception e) {
                        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                        io.codesentry.process.CancellableTasks.check();
                        continue;
                    }
                }
                if (minimumJava == null) continue;
                for (OnlineRemediationEvidence e : records.values()) {
                    if (e.fixedVersions.isEmpty() || !matching(targetResult, pkg, e.cve).isEmpty()) continue;
                    // Known affected input + fixed event + exact target no longer affected.
                    // Missing/unrecognized advisories alone never prove a fix.
                    boolean eligible = e.fixedVersions.stream().anyMatch(v ->
                            UnifiedDependencyRemediation.sameReleaseLine(target, v)
                                    && UnifiedDependencyRemediation.compareVersions(target, v) >= 0);
                    if (eligible) {
                        e.verifiedTargets.put(target, minimumJava); e.status = "FOUND";
                        e.sources.add(UnifiedDependencyRemediation.javaSource(pkg, target));
                    }
                }
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            io.codesentry.process.CancellableTasks.check();
            for (OnlineRemediationEvidence e : records.values()) {
                if (!e.verifiedTargets.isEmpty()) continue;
                e.status = error instanceof LimitReached ? "LIMIT_REACHED" : "QUERY_FAILED";
            }
        }
    }

    private JsonNode query(String pkg, String version, Map<String, JsonNode> responses, Budget budget) throws Exception {
        String key = pkg + "@" + version;
        if (responses.containsKey(key)) return responses.get(key);
        budget.check();
        JsonNode answer = remote.query(pkg, version);
        // An empty object is OSV's documented no-vulnerabilities response; error payloads are not.
        if (answer == null || !answer.isObject() || (!answer.isEmpty() && !answer.has("vulns") && !answer.has("next_page_token"))
                || answer.has("error") || answer.has("code")
                || (answer.has("vulns") && !answer.path("vulns").isArray())) throw new IllegalStateException("无效的漏洞查询响应");
        if (!answer.path("next_page_token").asText().isBlank()) throw new IllegalStateException("漏洞查询未完整返回，覆盖尚未核实");
        budget.responseBytes += answer.toString().length();
        if (budget.responseBytes > 4 * 1024 * 1024) throw new LimitReached();
        responses.put(key, answer); return answer;
    }

    static List<JsonNode> matching(JsonNode response, String pkg, String cve) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode advisory : response.path("vulns")) {
            if (advisory.hasNonNull("withdrawn")) continue;
            boolean id = cve.equals(advisory.path("id").asText());
            for (JsonNode alias : advisory.path("aliases")) id |= cve.equals(alias.asText());
            if (!id) continue;
            for (JsonNode affected : advisory.path("affected")) if (isPackage(affected, pkg)) { result.add(advisory); break; }
        }
        return result;
    }
    private static boolean isPackage(JsonNode affected, String pkg) {
        return "Maven".equals(affected.path("package").path("ecosystem").asText())
                && pkg.equals(affected.path("package").path("name").asText());
    }
    private static boolean stable(String value) { return value.matches("[0-9]+(?:\\.[0-9]+)*(?:(?:\\.Final|\\.RELEASE)|-(?:jre|android))?"); }
    public static String signature(FindingView f) { return f.getCodeSnippet() == null ? "" : f.getCodeSnippet().split("\\R",2)[0].trim(); }
    public static String coordinate(FindingView f) { String s = signature(f); int at = s.lastIndexOf('@'); return at < 0 ? "" : s.substring(0,at); }
    private static String installed(FindingView f) { String s = signature(f); int at = s.lastIndexOf('@'); return at < 0 ? "" : s.substring(at+1); }

    public static void attach(ScanRun run, List<FindingView> findings) {
        if (run.getRemediationEvidence() == null) return;
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, OnlineRemediationEvidence> entries = new LinkedHashMap<>();
            for (JsonNode item : mapper.readTree(run.getRemediationEvidence()).path("entries")) {
                OnlineRemediationEvidence e = mapper.treeToValue(item, OnlineRemediationEvidence.class);
                entries.put(e.coordinate + "@" + e.installedVersion + "|" + e.cve, e);
            }
            for (FindingView f : findings) f.setOnlineRemediation(entries.get(signature(f) + "|" + f.getRuleId()));
        } catch (Exception error) { throw new IllegalStateException("本次扫描的联网依据读取失败", error); }
    }

    private static class LimitReached extends RuntimeException { }
    private static class Budget {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        int queries;
        long responseBytes;
        void check() {
            io.codesentry.process.CancellableTasks.check();
            if (++queries > 100 || System.nanoTime() > deadline) throw new LimitReached();
        }
    }
}
