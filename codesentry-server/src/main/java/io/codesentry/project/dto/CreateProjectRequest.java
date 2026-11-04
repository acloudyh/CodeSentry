package io.codesentry.project.dto;

import io.codesentry.project.model.ProjectSourceType;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class CreateProjectRequest {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 128, message = "项目名称不能超过128个字符")
    private String projectName;

    @NotNull(message = "仓库来源不能为空")
    private ProjectSourceType sourceType;

    private String localPath;
    private String remoteUrl;
    private String defaultBranch;
    @javax.validation.constraints.Min(value = 8, message = "业务 Java 版本不能低于 8")
    @javax.validation.constraints.Max(value = 25, message = "业务 Java 版本不能高于 25")
    private Integer targetJavaVersion;
    public Integer getTargetJavaVersion() { return targetJavaVersion; }
    public void setTargetJavaVersion(Integer value) { targetJavaVersion = value; }


    public String getProjectName() {
        return projectName;
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

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }
}

