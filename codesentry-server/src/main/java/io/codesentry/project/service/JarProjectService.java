package io.codesentry.project.service;

import io.codesentry.common.error.BusinessException;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import io.codesentry.scan.service.JarUploadService;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class JarProjectService {
  private final ScanProjectMapper projects;
  private final JarUploadService uploads;

  public JarProjectService(ScanProjectMapper projects, JarUploadService uploads) {
    this.projects = projects;
    this.uploads = uploads;
  }

  @Transactional
  public ScanProject update(Long id, String name, Integer javaVersion, MultipartFile file) {
    JarUploadService.validateMetadata(name, javaVersion, true);
    ScanProject project = projects.findByIdForUpdate(id);
    if (project == null) throw new BusinessException(HttpStatus.NOT_FOUND, "项目不存在");
    if (project.getSourceType() != ProjectSourceType.JAR_UPLOAD
        || !Boolean.TRUE.equals(project.getEnabled())) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "仅支持编辑有效的 JAR 项目");
    }
    if (projects.countActiveTasks(id) > 0) {
      throw new BusinessException(HttpStatus.CONFLICT, "该项目有扫描或 AI 审核正在执行，请结束后再编辑");
    }
    JarUploadService.StoredJar stored = file == null ? null : uploads.store(file);
    if (stored != null && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED) uploads.cleanup(stored.getWorkspace());
            }
          });
    }
    try {
      project.setProjectName(name.trim());
      project.setTargetJavaVersion(javaVersion);
      if (stored != null) {
        project.setLocalPath(stored.getArchive().toString());
        project.setManagedPath(stored.getWorkspace().toString());
      }
      project.setUpdatedAt(LocalDateTime.now());
      if (projects.update(project) != 1)
        throw new BusinessException(HttpStatus.CONFLICT, "项目状态已变化，请刷新后重试");
      return project;
    } catch (RuntimeException error) {
      if (stored != null) uploads.cleanup(stored.getWorkspace());
      throw error;
    }
  }
}
