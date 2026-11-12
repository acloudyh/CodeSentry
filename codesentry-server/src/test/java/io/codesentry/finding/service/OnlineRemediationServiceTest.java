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
        verify(runs,times(3)).updateRemediation(run);
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
        run.setRemediationEvidence(null); // Force a fresh response for this pagination test.
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
    @Test void defaultBudgetCoversMoreThanOneHundredActualLookups() throws Exception {
        List<FindingView> group=new ArrayList<>();
        for(int n=0;n<60;n++) { FindingView f=finding("CVE-2023-1111");f.setCodeSnippet("sample:library-"+n+"@2.0.0");group.add(f); }
        when(findings.findByRunId(1L)).thenReturn(group);
        when(remote.query(anyString(),eq("2.0.0"))).thenAnswer(c -> response(advisory("CVE-2023-1111","2.0.1",c.getArgument(0))));
        when(remote.query(anyString(),eq("2.0.1"))).thenReturn(response());when(remote.minimumJava(anyString(),eq("2.0.1"))).thenReturn(8);
        ScanRun run=run();service.enrich(run);OnlineRemediationService.attach(run,group);
        assertEquals("COMPLETED",run.getRemediationStatus());assertTrue(group.stream().allMatch(f->"FOUND".equals(f.getOnlineRemediation().status)));
        verify(remote,times(120)).query(anyString(),anyString());verify(remote,times(60)).minimumJava(anyString(),anyString());
    }
    @Test void resumesSavedSuccessWithoutSlidingItsCacheExpiryAndRefreshesExpiredEvidence() throws Exception {
        FindingView f=finding("CVE-2023-1111");when(findings.findByRunId(1L)).thenReturn(List.of(f));
        when(remote.query(pkg,"2.0.0")).thenReturn(response(advisory(f.getRuleId(),"2.0.1",pkg)));when(remote.query(pkg,"2.0.1")).thenReturn(response());when(remote.minimumJava(pkg,"2.0.1")).thenReturn(8);
        ScanRun run=run();service.enrich(run);String origin=json.readTree(run.getRemediationEvidence()).path("cacheCreatedAt").asText();clearInvocations(remote);
        service.enrich(run);verifyNoInteractions(remote);assertEquals(origin,json.readTree(run.getRemediationEvidence()).path("cacheCreatedAt").asText());
        var saved=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(run.getRemediationEvidence());saved.put("cacheCreatedAt",java.time.Instant.now().minusSeconds(90000).toString());run.setRemediationEvidence(saved.toString());
        service.enrich(run);verify(remote).query(pkg,"2.0.0");
    }
    @Test void compatibilityCoverageStopsAdditionalCandidateQueries() throws Exception {
        FindingView a=finding("CVE-2023-1111"),b=finding("CVE-2023-2222");when(findings.findByRunId(1L)).thenReturn(List.of(a,b));
        var assets=mock(io.codesentry.project.mapper.ScanProjectMapper.class);var project=new io.codesentry.project.model.ScanProject();project.setTargetJavaVersion(8);when(assets.findById(1L)).thenReturn(project);org.springframework.test.util.ReflectionTestUtils.setField(service,"assets",assets);
        when(remote.query(pkg,"2.0.0")).thenReturn(response(advisory(a.getRuleId(),"2.0.2",pkg),advisory(b.getRuleId(),"2.0.3",pkg)));when(remote.query(pkg,"2.0.3")).thenReturn(response());when(remote.minimumJava(pkg,"2.0.3")).thenReturn(8);
        ScanRun run=run();run.setProjectId(1L);service.enrich(run);OnlineRemediationService.attach(run,List.of(a,b));
        assertEquals("FOUND",a.getOnlineRemediation().status);assertEquals("FOUND",b.getOnlineRemediation().status);verify(remote,never()).query(pkg,"2.0.2");
    }
    @Test void requestLimitReportsItsSpecificReasonWithoutClaimingAFix() throws Exception {
        var settings=new io.codesentry.config.CodeSentryProperties();settings.getRemediation().setMaxQueries(1);org.springframework.test.util.ReflectionTestUtils.setField(service,"properties",settings);
        FindingView f=finding("CVE-2023-1111");when(findings.findByRunId(1L)).thenReturn(List.of(f));when(remote.query(pkg,"2.0.0")).thenReturn(response(advisory(f.getRuleId(),"2.0.1",pkg)));
        ScanRun run=run();service.enrich(run);OnlineRemediationService.attach(run,List.of(f));assertEquals("LIMIT_REACHED",f.getOnlineRemediation().status);assertTrue(f.getOnlineRemediation().message.contains("请求数"));assertTrue(f.getOnlineRemediation().verifiedTargets.isEmpty());assertTrue(f.getOnlineRemediation().description().contains("可补充核实"));
    }

    @Test void reportGroupsIdenticalOnlineNotesWhileKeepingEveryCveAndScannerRecord() {
        FindingView a=finding("CVE-2023-1111"),b=finding("CVE-2023-2222");
        for(FindingView f:List.of(a,b)) { OnlineRemediationEvidence e=new OnlineRemediationEvidence();e.status="LIMIT_REACHED";e.checkedAt="2026-11-12T09:04:05.819952Z";f.setOnlineRemediation(e); }
        List<String> notes=DependencyRemediationAdvice.versionRecords(List.of(a,b));
        assertEquals(1,notes.stream().filter(x->x.contains("联网核实")).count());assertTrue(notes.get(0).contains(a.getRuleId()+"、"+b.getRuleId()));assertTrue(notes.get(0).contains("2026-11-12 17:04:05"));assertFalse(notes.get(0).contains("819952Z"));assertEquals(2,notes.stream().filter(x->x.contains("扫描时漏洞库记录")).count());
    }

}
