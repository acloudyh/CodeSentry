package io.codesentry.finding.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.finding.model.FindingView;
import io.codesentry.scan.mapper.ScanRunMapper;
import io.codesentry.scan.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in smoke test: no project database is written. */
@EnabledIfEnvironmentVariable(named = "CODESENTRY_LIVE_NETWORK_TEST", matches = "1")
class OnlineRemediationLiveTest {
  @Test
  void liveOsvLookupAndMavenBytecodeCheckAreSavedAndUsedByAdvice() throws Exception {
    ObjectMapper json = new ObjectMapper();
    RemediationHttpClient remote = new RemediationHttpClient(json);
    FindingPersistenceService findings = mock(FindingPersistenceService.class);
    ScanRunMapper runs = mock(ScanRunMapper.class);
    List<FindingView> group = new ArrayList<>();
    for (String cve :
        List.of("CVE-2023-42276", "CVE-2023-42277", "CVE-2023-42278", "CVE-2023-51075")) {
      FindingView f = new FindingView();
      f.setRuleId(cve);
      f.setCodeSnippet("cn.hutool:hutool-core@5.8.20");
      f.setScanner("Trivy");
      f.setCategory("VULNERABILITY");
      group.add(f);
    }
    when(findings.findByRunId(1L)).thenReturn(group);
    when(runs.updateRemediation(any())).thenReturn(1);
    ScanRun run = new ScanRun();
    run.setId(1L);
    run.setNetworkMode(NetworkMode.ONLINE);
    run.setStatus(ScanRunStatus.RUNNING);
    new OnlineRemediationService(remote, json, findings, runs).enrich(run);
    assertNotNull(run.getRemediationEvidence());
    Files.writeString(Path.of("target/online-live-evidence.json"), run.getRemediationEvidence());
    OnlineRemediationService.attach(run, group);
    assertEquals("COMPLETED", run.getRemediationStatus(), run.getRemediationMessage());
    assertTrue(group.stream().allMatch(f -> !f.getOnlineRemediation().sources.isEmpty()));
    assertTrue(
        group.stream().anyMatch(f -> !f.getOnlineRemediation().verifiedTargets.isEmpty()),
        run.getRemediationEvidence());
    assertTrue(DependencyRemediationAdvice.compact(group, 8).contains("hutool-core:5.8.24"));
    assertEquals(8, remote.minimumJava("cn.hutool:hutool-all", "5.8.24"));
  }
}
