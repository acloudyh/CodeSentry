package io.codesentry.finding.service;

import com.fasterxml.jackson.databind.*;
import io.codesentry.finding.model.*;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnlineRemediationServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final RemediationHttpClient remote = mock(RemediationHttpClient.class);
    final FindingPersistenceService findings = mock(FindingPersistenceService.class);
    final ScanRunMapper runs = mock(ScanRunMapper.class);
    final OnlineRemediationService service = new OnlineRemediationService(remote, json, findings, runs);
    final String pkg = "example:library";

    ScanRun run() {
        ScanRun run = new ScanRun(); run.setId(1L); run.setNetworkMode(NetworkMode.ONLINE); run.setStatus(ScanRunStatus.RUNNING);
        when(runs.updateRemediation(any())).thenReturn(1); return run;
    }
    FindingView finding(String cve) {
        FindingView f = new FindingView(); f.setScanner("Trivy"); f.setCategory("VULNERABILITY");
        f.setRuleId(cve); f.setCodeSnippet(pkg + "@2.0.0"); f.setRecommendation("升级到修复版本：请查阅漏洞公告"); return f;
    }
    JsonNode advisory(String cve, String fixed, String coordinate) throws Exception {
        return json.readTree("{\"id\":\"GHSA-test\",\"aliases\":[\"" + cve + "\"],\"affected\":[{\"package\":{\"ecosystem\":\"Maven\",\"name\":\""
                + coordinate + "\"},\"ranges\":[{\"type\":\"ECOSYSTEM\",\"events\":[{\"introduced\":\"0\"}"
                + (fixed == null ? "" : ",{\"fixed\":\"" + fixed + "\"}") + "]}]}]}");
    }
    JsonNode response(JsonNode... records) { return json.createObjectNode().set("vulns", json.valueToTree(records)); }

    @Test void exactTargetChecksCompleteAGroupAndSurviveSnapshotReloadWithoutEditingScannerFacts() throws Exception {
        FindingView a = finding("CVE-2023-1111"), b = finding("CVE-2023-2222");
        when(findings.findByRunId(1L)).thenReturn(List.of(a,b));
        JsonNode va = advisory(a.getRuleId(), "2.0.1", pkg), vb = advisory(b.getRuleId(), "2.0.3", pkg);
        when(remote.query(pkg,"2.0.0")).thenReturn(response(va,vb));
        when(remote.query(pkg,"2.0.3")).thenReturn(response());
        when(remote.query(pkg,"2.0.1")).thenReturn(response(vb));
        when(remote.minimumJava(eq(pkg),anyString())).thenReturn(8);
        ScanRun run = run(); service.enrich(run);
        assertEquals("COMPLETED", run.getRemediationStatus());
        assertTrue(run.getRemediationEvidence().contains("responses"));
        OnlineRemediationService.attach(run,List.of(a,b));
        assertEquals(Set.of("2.0.3","2.0.1"), a.getOnlineRemediation().verifiedTargets.keySet());
        assertEquals(Set.of("2.0.3"), b.getOnlineRemediation().verifiedTargets.keySet());
        String body = DependencyRemediationAdvice.compact(List.of(a,b),8);
        assertTrue(body.contains("统一候选版本：example:library:2.0.3")); assertTrue(body.contains("2 / 2 条"));
        assertTrue(body.contains("Java 兼容性待确认"));
        assertEquals("升级到修复版本：请查阅漏洞公告",a.getRecommendation());
        assertFalse(String.join("\n",DependencyRemediationAdvice.versionRecords(List.of(a))).contains("官方补充"));
        assertTrue(a.getOnlineRemediation().description().contains("Java 要求至少 8"));
        verify(runs,times(2)).updateRemediation(run);
    }

    @Test void outageFallsBackAndNeverClaimsThatNoFixExists() throws Exception {
        FindingView a = finding("CVE-2023-1111"); when(findings.findByRunId(1L)).thenReturn(List.of(a));
        when(remote.query(pkg,"2.0.0")).thenThrow(new IOException("timeout"));
        ScanRun run = run(); service.enrich(run); OnlineRemediationService.attach(run,List.of(a));
        assertEquals("PARTIAL",run.getRemediationStatus()); assertEquals("QUERY_FAILED",a.getOnlineRemediation().status);
        assertTrue(a.getOnlineRemediation().verifiedTargets.isEmpty());
        assertTrue(a.getOnlineRemediation().description().contains("联网查询失败"));
    }

    @Test void anotherCoordinateOrWithdrawnAdvisoryCannotSupplyAFix() throws Exception {
        FindingView a=finding("CVE-2023-1111"); when(findings.findByRunId(1L)).thenReturn(List.of(a));
        JsonNode withdrawn=advisory(a.getRuleId(),"2.0.1",pkg); ((com.fasterxml.jackson.databind.node.ObjectNode)withdrawn).put("withdrawn","2025-01-01");
        when(remote.query(pkg,"2.0.0")).thenReturn(response(advisory(a.getRuleId(),"2.0.1","other:library"),withdrawn));
        ScanRun run=run(); service.enrich(run); OnlineRemediationService.attach(run,List.of(a));
        assertEquals("NOT_FOUND",a.getOnlineRemediation().status); verify(remote,never()).minimumJava(any(),any());
    }

    @Test void newerJavaRequirementIsRecordedButNeverChangesTheProjectConstraint() throws Exception {
        FindingView a=finding("CVE-2023-1111"); when(findings.findByRunId(1L)).thenReturn(List.of(a));
        when(remote.query(pkg,"2.0.0")).thenReturn(response(advisory(a.getRuleId(),"2.0.1",pkg)));
        when(remote.query(pkg,"2.0.1")).thenReturn(response()); when(remote.minimumJava(pkg,"2.0.1")).thenReturn(17);
        ScanRun run=run(); service.enrich(run); OnlineRemediationService.attach(run,List.of(a));
        assertFalse(DependencyRemediationAdvice.compact(List.of(a),8).contains("example:library:2.0.1"));
        assertTrue(DependencyRemediationAdvice.compact(List.of(a),17).contains("example:library:2.0.1"));
    }

    @Test void incompletePaginationOrStillAffectedTargetCannotProveCoverage() throws Exception {
        FindingView a=finding("CVE-2023-1111"); when(findings.findByRunId(1L)).thenReturn(List.of(a));
        JsonNode record=advisory(a.getRuleId(),"2.0.1",pkg);
        when(remote.query(pkg,"2.0.0")).thenReturn(response(record));
        when(remote.query(pkg,"2.0.1")).thenReturn(response(record)); when(remote.minimumJava(pkg,"2.0.1")).thenReturn(8);
        ScanRun run=run(); service.enrich(run); OnlineRemediationService.attach(run,List.of(a));
        assertEquals("UNVERIFIED",a.getOnlineRemediation().status);
        when(remote.query(pkg,"2.0.1")).thenReturn(json.readTree("{\"next_page_token\":\"more\"}"));
        service.enrich(run); OnlineRemediationService.attach(run,List.of(a));
        assertEquals("QUERY_FAILED",a.getOnlineRemediation().status);
    }

    @Test void absentFixIsRecordedAsMissingFromTheSourceRatherThanGloballyUnfixable() throws Exception {
        FindingView a=finding("CVE-2023-1111"); when(findings.findByRunId(1L)).thenReturn(List.of(a));
        when(remote.query(pkg,"2.0.0")).thenReturn(response(advisory(a.getRuleId(),null,pkg)));
        ScanRun run=run(); service.enrich(run); OnlineRemediationService.attach(run,List.of(a));
        assertEquals("NO_FIXED_VERSION",a.getOnlineRemediation().status);
        assertTrue(a.getOnlineRemediation().description().contains("所查询记录未列修复版本"));
    }
}
