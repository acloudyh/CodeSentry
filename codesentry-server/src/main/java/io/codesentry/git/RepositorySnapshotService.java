package io.codesentry.git;

import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import io.codesentry.process.CommandResult;
import io.codesentry.project.model.ScanProject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RepositorySnapshotService {

  private final GitRepositoryService gitRepositoryService;
  private final CodeSentryProperties properties;

  public RepositorySnapshotService(
      GitRepositoryService gitRepositoryService, CodeSentryProperties properties) {
    this.gitRepositoryService = gitRepositoryService;
    this.properties = properties;
  }

  public Path exportCommit(ScanProject project, String commit, Long runId, String label) {
    Path snapshotRoot =
        properties
            .getStorage()
            .rootPath()
            .resolve(properties.getStorage().getSnapshotsDirectory())
            .resolve(String.valueOf(runId));
    Path target = snapshotRoot.resolve(label).normalize();
    Path archive = snapshotRoot.resolve(label + ".tar");

    try {
      Files.createDirectories(target);
      CommandResult result =
          gitRepositoryService.git(
              gitRepositoryService.repositoryPath(project),
              "archive",
              "--format=tar",
              "--output=" + archive,
              commit);
      if (!result.isSuccess()) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "导出 Git Commit 快照失败");
      }
      extractTar(archive, target);
      Files.deleteIfExists(archive);
      return target;
    } catch (IOException exception) {
      throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "创建扫描快照失败");
    }
  }

  private void extractTar(Path archive, Path target) throws IOException {
    try (InputStream fileInput = Files.newInputStream(archive);
        TarArchiveInputStream tarInput =
            new TarArchiveInputStream(new BufferedInputStream(fileInput))) {
      TarArchiveEntry entry;
      while ((entry = tarInput.getNextTarEntry()) != null) {
        Path output = target.resolve(entry.getName()).normalize();
        if (!output.startsWith(target)) {
          throw new IOException("Git 快照中包含越界路径");
        }
        if (entry.isDirectory()) {
          Files.createDirectories(output);
          continue;
        }
        if (entry.isSymbolicLink()) {
          continue;
        }
        Files.createDirectories(output.getParent());
        try (OutputStream fileOutput = new BufferedOutputStream(Files.newOutputStream(output))) {
          tarInput.transferTo(fileOutput);
        }
        if ((entry.getMode() & 0100) != 0) {
          output.toFile().setExecutable(true, true);
        }
      }
    }
  }
}
