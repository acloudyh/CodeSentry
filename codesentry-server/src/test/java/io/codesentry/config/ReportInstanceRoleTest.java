package io.codesentry.config;

import io.codesentry.finding.service.RemediationRefreshService;
import io.codesentry.finding.service.OnlineRemediationService;
import io.codesentry.git.GitRepositoryService;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.service.RepositorySyncService;
import io.codesentry.scan.mapper.ScanRunMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** 报告专用实例与原后台任务共存时，不允许执行启动恢复或认领 Git 拉取。 */
class ReportInstanceRoleTest {
    @Test void reportInstanceDoesNotRegisterCompatibilityRecoveryRunner() {
        new ApplicationContextRunner().withBean(DataSource.class, () -> mock(DataSource.class))
                .withUserConfiguration(DatabaseCompatibilityInitializer.class)
                .withPropertyValues("codesentry.runtime.worker-enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(DatabaseCompatibilityInitializer.class).isEmpty()));
    }
    @Test void reportInstanceDoesNotRecoverOrDispatchWorkers() {
        ScanProjectMapper projects=mock(ScanProjectMapper.class);
        PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        RepositorySyncService git=new RepositorySyncService(projects,mock(GitRepositoryService.class),new SyncTaskExecutor(),tx);
        ReflectionTestUtils.setField(git,"workerEnabled",false);
        git.ready(); git.dispatch(); verifyNoInteractions(projects);
        ScanRunMapper runs=mock(ScanRunMapper.class);
        RemediationRefreshService refresh=new RemediationRefreshService(runs,mock(OnlineRemediationService.class),new SyncTaskExecutor(),tx);
        ReflectionTestUtils.setField(refresh,"workerEnabled",false);
        refresh.recover(); verifyNoInteractions(runs);
    }
}
