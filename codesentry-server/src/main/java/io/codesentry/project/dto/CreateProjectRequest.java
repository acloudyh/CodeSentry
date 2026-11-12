package io.codesentry.project.dto;

import io.codesentry.project.model.ProjectSourceType;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

public class CreateProjectRequest {
    private io.codesentry.project.model.ApplicationType applicationType;
    @Size(max=32, message="Node.js 版本最多32个字符")
    @javax.validation.constraints.Pattern(regexp="v?[0-9]{1,3}(\\.[0-9]{1,3}){0,2}([-+][0-9A-Za-z.-]+)?", message="请填写有效的 Node.js 版本，例如 18、20.19.0")
    private String targetNodeVersion;
    @Size(max=256, message="Git 账号最多256个字符")
    @javax.validation.constraints.Pattern(regexp="[^\\r\\n\\x00]*", message="Git 账号不能包含换行或空字符")
    private String gitUsername;
    @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private String gitPassword;
    private boolean clearGitCredentials;
    public io.codesentry.project.model.ApplicationType getApplicationType() { return applicationType; }
    public void setApplicationType(io.codesentry.project.model.ApplicationType value) { applicationType=value; }
    public String getTargetNodeVersion() { return targetNodeVersion; }
    public void setTargetNodeVersion(String value) { targetNodeVersion=value; }
    public String getGitUsername() { return gitUsername; }
    public void setGitUsername(String value) { gitUsername=value; }
    @com.fasterxml.jackson.annotation.JsonIgnore
    @javax.validation.constraints.AssertTrue(message="Git 密码或令牌最多4000个字符，不能包含换行或空字符")
    public boolean isGitPasswordValid() {
        return gitPassword == null || (gitPassword.length() <= 4000 && gitPassword.indexOf('\r') < 0 && gitPassword.indexOf('\n') < 0 && gitPassword.indexOf(0) < 0);
    }
    public String getGitPassword() { return gitPassword; }
    public void setGitPassword(String value) { gitPassword=value; }
    public boolean getClearGitCredentials() { return clearGitCredentials; }
    public void setClearGitCredentials(boolean value) { clearGitCredentials=value; }


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

