package io.codesentry.project.model;

import java.time.LocalDateTime;

public class ScanProject {
    private Long gitCredentialId;
    /** 集中维护的 Git 账号 ID；为空时兼容仓库原有独立凭据。 */
    public Long getGitCredentialId() { return gitCredentialId; }
    public void setGitCredentialId(Long value) { gitCredentialId=value; }
    private io.codesentry.project.model.ApplicationType applicationType = io.codesentry.project.model.ApplicationType.BACKEND;
    public io.codesentry.project.model.ApplicationType getApplicationType() { return applicationType; }
    public void setApplicationType(io.codesentry.project.model.ApplicationType value) { applicationType = value; }
    private String targetNodeVersion;
    public String getTargetNodeVersion() { return targetNodeVersion; }
    public void setTargetNodeVersion(String value) { targetNodeVersion = value; }
    private String gitUsername;
    public String getGitUsername() { return gitUsername; }
    public void setGitUsername(String value) { gitUsername = value; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String gitPasswordCiphertext;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getGitPasswordCiphertext() { return gitPasswordCiphertext; }
    public void setGitPasswordCiphertext(String value) { gitPasswordCiphertext = value; }
    private java.time.LocalDateTime lastSyncedAt;
    public java.time.LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(java.time.LocalDateTime value) { lastSyncedAt = value; }
    private String lastSyncStatus;
    public String getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(String value) { lastSyncStatus = value; }
    private String lastSyncMessage;
    public String getLastSyncMessage() { return lastSyncMessage; }
    public void setLastSyncMessage(String value) { lastSyncMessage = value; }
    public boolean isGitCredentialConfigured() { return gitCredentialId != null || (gitPasswordCiphertext != null && !gitPasswordCiphertext.isEmpty()); }


    private long repositoryRevision;
    public long getRepositoryRevision() { return repositoryRevision; }
    public void setRepositoryRevision(long value) { repositoryRevision = value; }
    private Long businessProjectId;
    public Long getBusinessProjectId() { return businessProjectId; }
    public void setBusinessProjectId(Long value) { businessProjectId = value; }
    private Long id;
    private String projectName;
    private ProjectSourceType sourceType;
    private String localPath;
    private String remoteUrl;
    private String managedPath;
    private String defaultBranch;
    private Integer targetJavaVersion;
    public Integer getTargetJavaVersion() { return targetJavaVersion; }
    public void setTargetJavaVersion(Integer value) { targetJavaVersion = value; }

    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProjectName() {
        return projectName;
    }

    /** JAR registrations retain their unique storage name; user-facing names omit its UUID suffix. */
    public String getDisplayName() {
        if (sourceType != ProjectSourceType.JAR_UPLOAD || projectName == null) return projectName;
        return projectName.replaceFirst(" · [0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$", "");
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public ProjectSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(ProjectSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getManagedPath() {
        return managedPath;
    }

    public void setManagedPath(String managedPath) {
        this.managedPath = managedPath;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

