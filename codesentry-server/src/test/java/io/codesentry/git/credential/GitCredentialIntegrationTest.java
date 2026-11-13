package io.codesentry.git.credential;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.ai.secret.AiToolSecretCipher;
import io.codesentry.common.error.BusinessException;
import io.codesentry.git.*;
import io.codesentry.process.*;
import io.codesentry.project.dto.*;
import io.codesentry.project.model.*;
import io.codesentry.project.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.*;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实数据库校验集中账号、加密、不回显、来源限制、引用保护及后续密码轮换。 */
@SpringBootTest(properties={"spring.main.web-application-type=none","logging.level.root=WARN","codesentry.ai.enabled=false"})
@EnabledIfEnvironmentVariable(named="CODESENTRY_INTEGRATION_DB_URL",matches=".*codesentry_business_test_.*")
@Transactional
class GitCredentialIntegrationTest {
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("CODESENTRY_INTEGRATION_DB_URL"));
        r.add("codesentry.storage.root",()->System.getProperty("java.io.tmpdir")+"/codesentry-shared-git-test");
    }
    @Autowired GitCredentialService profiles;
    @Autowired ScanProjectService projects;
    @Autowired GitCredentialAccess access;
    @Autowired AiToolSecretCipher cipher;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @MockBean GitRepositoryService git;
    @MockBean RepositorySyncService sync;

    /** 两仓库复用一个引用，修改密码后实际 Git 环境使用新值，API 中不出现秘密。 */
    @Test void sharedAccountRemainsEncryptedAndUsesLatestSecretForEveryRepository() throws Exception {
        GitCredentialRequest r=request();Map<String,Object> saved=profiles.save(null,r);Long id=((Number)saved.get("id")).longValue();
        assertFalse(json.writeValueAsString(saved).contains(r.password));assertFalse(json.writeValueAsString(profiles.list()).contains("password"));
        String encrypted=db.queryForObject("SELECT password_ciphertext FROM git_credential WHERE id=?",String.class,id);
        assertNotEquals(r.password,encrypted);assertEquals(r.password,cipher.decrypt(encrypted));
        ScanProject a=projects.create(repository(id,"https://git.example.test/group/a.git"));
        ScanProject b=projects.create(repository(id,"https://git.example.test/group/b.git"));
        assertEquals(id,a.getGitCredentialId());assertNull(a.getGitPasswordCiphertext());assertTrue(a.isGitCredentialConfigured());
        assertFalse(json.writeValueAsString(a).contains(encrypted));assertFalse(json.writeValueAsString(a).contains(r.password));
        assertEquals(2,((Number)profiles.list().stream().filter(x->x.get("id").equals(id)).findFirst().orElseThrow().get("usageCount")).intValue());
        r.password="fixture-rotated-not-a-real-secret";profiles.save(id,r);
        CommandExecutor executor=mock(CommandExecutor.class);
        when(executor.execute(any(),anyList(),any(),anyMap(),any())).thenAnswer(call->{
            Map<String,String> env=call.getArgument(3);assertEquals(r.password,env.get("CODESENTRY_GIT_PASSWORD"));assertEquals(r.username,env.get("CODESENTRY_GIT_USERNAME"));
            List<String> argv=call.getArgument(1);assertFalse(argv.toString().contains(r.password));
            UnaryOperator<String> redact=call.getArgument(4);assertEquals("[REDACTED]",redact.apply(r.password));return new CommandResult(0,"");
        });
        access.execute(executor,null,List.of("git","ls-remote",a.getRemoteUrl()),Duration.ofSeconds(1),a);
        access.execute(executor,null,List.of("git","ls-remote",b.getRemoteUrl()),Duration.ofSeconds(1),b);
        String current=db.queryForObject("SELECT password_ciphertext FROM git_credential WHERE id=?",String.class,id);r.password="";profiles.save(id,r);assertEquals(current,db.queryForObject("SELECT password_ciphertext FROM git_credential WHERE id=?",String.class,id));
        verify(executor,times(2)).execute(any(),anyList(),any(),anyMap(),any());
    }

    /** 不同主机、端口或协议不能使用选中的账号，防止误选后向其他服务发送凭据。 */
    @Test void selectionAndValidationRejectAnotherOriginAndLegacyAccountsStillWork() {
        GitCredentialRequest r=request();Long id=((Number)profiles.save(null,r).get("id")).longValue();
        assertThrows(BusinessException.class,()->projects.create(repository(id,"https://another.example.test/a.git")));
        assertThrows(BusinessException.class,()->profiles.resolve(id,"http://git.example.test/a.git"));
        assertThrows(BusinessException.class,()->profiles.resolve(id,"https://git.example.test:8443/a.git"));
        GitValidationRequest v=new GitValidationRequest();v.setRemoteUrl("https://another.example.test/a.git");v.setGitCredentialId(id);assertThrows(BusinessException.class,()->projects.validateGit(v));
        CreateProjectRequest inline=repository(null,"https://git.example.test/legacy.git");inline.setGitUsername("fixture-user");inline.setGitPassword("fixture-legacy-not-a-real-secret");ScanProject old=projects.create(inline);assertNull(old.getGitCredentialId());assertTrue(old.isGitCredentialConfigured());assertEquals(inline.getGitPassword(),cipher.decrypt(old.getGitPasswordCiphertext()));
    }

    /** 被引用账号不能删除；解除最后引用后可删除，不会删除任何仓库记录。 */
    @Test void referencedAccountCannotBeDeletedOrMovedToAnotherServer() {
        GitCredentialRequest r=request();Long id=((Number)profiles.save(null,r).get("id")).longValue();ScanProject asset=projects.create(repository(id,"https://git.example.test/a.git"));
        assertThrows(BusinessException.class,()->profiles.delete(id));r.serverUrl="https://another.example.test";assertThrows(BusinessException.class,()->profiles.save(id,r));
        db.update("UPDATE scan_project SET git_credential_id=NULL WHERE id=?",asset.getId());profiles.delete(id);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM git_credential WHERE id=?",Integer.class,id));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM scan_project WHERE id=?",Integer.class,asset.getId()));
    }
    private GitCredentialRequest request() { GitCredentialRequest r=new GitCredentialRequest();r.name="Fixture Git";r.serverUrl="https://git.example.test:443/";r.username="fixture-user";r.password="fixture-initial-not-a-real-secret";return r; }
    private CreateProjectRequest repository(Long id,String url) { CreateProjectRequest r=new CreateProjectRequest();r.setProjectName("Fixture asset "+Integer.toUnsignedString(url.hashCode()));r.setSourceType(ProjectSourceType.GIT);r.setRemoteUrl(url);r.setGitCredentialId(id);return r; }
}
