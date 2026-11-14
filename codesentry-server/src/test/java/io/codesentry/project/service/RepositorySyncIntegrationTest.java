package io.codesentry.project.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.git.GitRepositoryService;
import io.codesentry.project.dto.CreateProjectRequest;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.*;
import io.codesentry.scan.service.ScanOrchestrator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.main.web-application-type=none", "logging.level.root=WARN", "codesentry.ai.enabled=false"})
@DirtiesContext
@EnabledIfEnvironmentVariable(named="CODESENTRY_INTEGRATION_DB_URL",matches=".*codesentry_business_test_.*")
class RepositorySyncIntegrationTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("CODESENTRY_INTEGRATION_DB_URL"));
        r.add("codesentry.storage.root",()->System.getProperty("java.io.tmpdir")+"/codesentry-async-integration");
    }
    @MockBean GitRepositoryService git;
    @MockBean ScanOrchestrator scans;
    @Autowired ScanProjectService service;
    @Autowired RepositorySyncService worker;
    @Autowired ScanProjectMapper mapper;
    @Autowired JdbcTemplate db;
    CreateProjectRequest request(String url, String name) {
        CreateProjectRequest r=new CreateProjectRequest();r.setProjectName(name);r.setSourceType(ProjectSourceType.GIT);r.setRemoteUrl(url);r.setDefaultBranch("main");return r;
    }
    void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean());
    }
    @Test void slowPullNeverBlocksSaveAndStaleCompletionCannotReplaceNewRemote() throws Exception {
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),secondStarted=new CountDownLatch(1),secondRelease=new CountDownLatch(1);
        String old="https://example.invalid/old.git", next="https://example.invalid/new.git";
        when(git.cloneMirror(eq(old),any())).thenAnswer(call->{started.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return Path.of("/cache/old.git");});
        when(git.cloneMirror(eq(next),any())).thenAnswer(call->{secondStarted.countDown();assertTrue(secondRelease.await(10,TimeUnit.SECONDS));return Path.of("/cache/new.git");});
        ScanProject p=assertTimeout(Duration.ofSeconds(2),()->service.create(request(old,"initial")));
        try {
            assertNull(p.getManagedPath());assertEquals("PENDING",p.getLastSyncStatus());worker.dispatch();assertTrue(started.await(5,TimeUnit.SECONDS));
            assertThrows(BusinessException.class,()->service.requireReadyForScan(p.getId()));
            ScanProject edited=assertTimeout(Duration.ofSeconds(2),()->service.update(p.getId(),request(old,"edited while pulling")));
            assertEquals("RUNNING",edited.getLastSyncStatus());
            assertTimeout(Duration.ofSeconds(2),()->service.update(p.getId(),request(next,"new source")));
            long revision=service.requireById(p.getId()).getRepositoryRevision();
            service.sync(p.getId());assertEquals(revision,service.requireById(p.getId()).getRepositoryRevision());
            release.countDown();await(()->!worker.isRunning(p.getId()));
            assertNull(service.requireById(p.getId()).getManagedPath());
            // 调度器可能已认领新来源；不能把合法的 RUNNING 当作旧结果覆盖错误。
            assertTrue(java.util.Set.of("PENDING","RUNNING").contains(service.requireById(p.getId()).getLastSyncStatus()));
            worker.dispatch();assertTrue(secondStarted.await(5,TimeUnit.SECONDS));secondRelease.countDown();await(()->"COMPLETED".equals(service.requireById(p.getId()).getLastSyncStatus()));
            assertEquals("/cache/new.git",service.requireById(p.getId()).getManagedPath());assertEquals("new source",service.requireById(p.getId()).getProjectName());
            doThrow(new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST,"fixture network failure")).when(git).refresh(any());
            service.sync(p.getId());worker.dispatch();await(()->"FAILED".equals(service.requireById(p.getId()).getLastSyncStatus()));assertEquals("/cache/new.git",service.requireById(p.getId()).getManagedPath());
            doNothing().when(git).refresh(any());service.sync(p.getId());worker.dispatch();await(()->"COMPLETED".equals(service.requireById(p.getId()).getLastSyncStatus()));
            db.update("UPDATE scan_project SET last_sync_status='RUNNING' WHERE id=?",p.getId());worker.ready();assertEquals("FAILED",service.requireById(p.getId()).getLastSyncStatus());
        } finally { release.countDown();secondRelease.countDown();service.delete(p.getId()); }
    }
}
