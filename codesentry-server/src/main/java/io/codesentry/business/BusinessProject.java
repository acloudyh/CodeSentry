package io.codesentry.business;

public class BusinessProject {
    public Long id;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String name;
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String description;
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public java.time.LocalDateTime createdAt;
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime value) { createdAt = value; }
    public java.time.LocalDateTime updatedAt;
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime value) { updatedAt = value; }
    public int repositoryCount;
    public int getRepositoryCount() { return repositoryCount; }
    public void setRepositoryCount(int value) { repositoryCount = value; }
    public int jarCount;
    public int getJarCount() { return jarCount; }
    public void setJarCount(int value) { jarCount = value; }
    public ProjectScanRound latestRound;
    public ProjectScanRound getLatestRound() { return latestRound; }
    public void setLatestRound(ProjectScanRound value) { latestRound = value; }
}
