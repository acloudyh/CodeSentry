package io.codesentry.git;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import io.codesentry.ai.secret.AiToolSecretCipher;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.*;
import io.codesentry.project.model.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class GitCredentialAccessTest {
  @TempDir Path temp;
  private final CommandExecutor commands = new CommandExecutor();
  private final String username = "git-test-user";
  private final String password = "unit-only-p@ss '$() token";
  private HttpServer server;
  private Path source, remote;
  private String url;
  private GitRepositoryService git;
  private AiToolSecretCipher cipher;
  private AtomicInteger requests = new AtomicInteger();
  private List<CommandTrace> traces = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    CodeSentryProperties props = new CodeSentryProperties();
    props.getStorage().setRoot(temp.resolve("storage").toString());
    cipher = new AiToolSecretCipher(props);
    git = new GitRepositoryService(commands, props);
    ReflectionTestUtils.setField(git, "credentialAccess", new GitCredentialAccess(cipher));
    source = temp.resolve("source");
    Files.createDirectories(source);
    cmd(source, "init", "--initial-branch=main");
    Files.writeString(source.resolve("example.txt"), "first\n");
    commit();
    remote = temp.resolve("remote.git");
    cmd(null, "clone", "--bare", source.toString(), remote.toString());
    cmd(remote, "update-server-info");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    HttpContext context =
        server.createContext(
            "/",
            exchange -> {
              requests.incrementAndGet();
              Path file =
                  remote
                      .resolve(exchange.getRequestURI().getPath().replaceFirst("^/repo.git/?", ""))
                      .normalize();
              if (!file.startsWith(remote) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
              }
              byte[] body = Files.readAllBytes(file);
              exchange.sendResponseHeaders(200, body.length);
              exchange.getResponseBody().write(body);
              exchange.close();
            });
    context.setAuthenticator(
        new BasicAuthenticator("git-test") {
          @Override
          public boolean checkCredentials(String user, String pass) {
            return username.equals(user) && password.equals(pass);
          }
        });
    server.start();
    url = "http://127.0.0.1:" + server.getAddress().getPort() + "/repo.git";
    CommandTraceHolder.listen(traces::add);
  }

  @AfterEach
  void cleanup() {
    if (server != null) server.stop(0);
    CommandTraceHolder.clear();
  }

  private void cmd(Path dir, String... args) {
    List<String> c = new ArrayList<>(List.of("git"));
    c.addAll(Arrays.asList(args));
    CommandResult r = commands.execute(dir, c, Duration.ofSeconds(10));
    assertEquals(0, r.getExitCode(), r.getOutput());
  }

  private void commit() {
    cmd(source, "add", ".");
    cmd(
        source,
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "commit",
        "-m",
        "test");
  }

  private ScanProject project(String password) {
    ScanProject p = new ScanProject();
    p.setSourceType(ProjectSourceType.GIT);
    p.setRemoteUrl(url);
    p.setGitUsername(username);
    p.setGitPasswordCiphertext(cipher.encrypt(password));
    return p;
  }

  @Test
  void authenticatedCloneCachesCodeAndSyncOnlyUpdatesTheExistingMirror() throws Exception {
    ScanProject p = project(password);
    Path cached = git.cloneMirror(url, p);
    p.setManagedPath(cached.toString());
    String before = git.resolveCommit(p, "main");
    String config = Files.readString(cached.resolve("config"));
    assertTrue(config.contains(url));
    assertFalse(config.contains(password));
    assertFalse(config.contains(username));
    Files.writeString(source.resolve("example.txt"), "second\n");
    commit();
    cmd(remote, "fetch", source.toString(), "main:main");
    cmd(remote, "update-server-info");
    int beforeRead = requests.get();
    assertEquals(before, git.resolveCommit(p, "main"));
    assertEquals(beforeRead, requests.get(), "reading the local cache must not contact the server");
    git.refresh(p);
    assertEquals(cached.toString(), p.getManagedPath());
    assertNotEquals(before, git.resolveCommit(p, "main"));
    for (CommandTrace trace : traces) {
      assertFalse(trace.getCommandLine().contains(password));
      assertFalse(trace.getOutput().contains(password));
    }
    String encoded = new ObjectMapper().writeValueAsString(p);
    assertTrue(encoded.contains("gitCredentialConfigured\":true"));
    assertFalse(encoded.contains(password));
    assertFalse(encoded.contains("gitPasswordCiphertext"));
    assertFalse(encoded.contains(p.getGitPasswordCiphertext()));
  }

  @Test
  void authenticationFailureHasActionableMessageAndNoSecretInTrace() {
    String wrong = "unit-only-wrong-token";
    BusinessException error =
        assertThrows(BusinessException.class, () -> git.cloneMirror(url, project(wrong)));
    assertTrue(error.getMessage().contains("认证失败"));
    assertFalse(error.getMessage().contains(wrong));
    assertNotNull(CommandTraceHolder.get());
    assertFalse(CommandTraceHolder.get().getCommandLine().contains(wrong));
    assertFalse(CommandTraceHolder.get().getOutput().contains(wrong));
  }

  @Test
  void refusesCredentialsEmbeddedInRemoteUrls() {
    assertThrows(
        BusinessException.class,
        () -> git.cloneMirror("https://user:token@example.invalid/repo.git"));
  }

  @Test
  void validationReadsBranchesWithoutCloningOrPersistingCredentials() throws Exception {
    io.codesentry.project.dto.GitValidationResult result =
        git.validateConnection(url, username, password, null);
    assertTrue(result.valid);
    assertEquals("SUCCESS", result.code);
    assertEquals("main", result.defaultBranch);
    assertEquals(List.of("main"), result.branches);
    assertFalse(Files.exists(temp.resolve("storage/repositories")));
    assertFalse(Files.exists(temp.resolve("storage/secrets/ai-tools-master.key")));
    assertTrue(
        traces.stream()
            .noneMatch(
                t -> t.getOutput().contains(password) || t.getCommandLine().contains(password)));
  }

  @Test
  void validationReturnsAnAuthenticationResultWithoutDownloadingCode() {
    io.codesentry.project.dto.GitValidationResult result =
        git.validateConnection(url, username, "fixture-invalid-password", null);
    assertFalse(result.valid);
    assertEquals("AUTHENTICATION_ERROR", result.code);
    assertFalse(Files.exists(temp.resolve("storage/repositories")));
  }
}
