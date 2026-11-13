package io.codesentry.project.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.*;

public class GitValidationRequest {
    private Long gitCredentialId;
    /** 集中维护的 Git 账号 ID；为空时兼容仓库原有独立凭据。 */
    public Long getGitCredentialId() { return gitCredentialId; }
    public void setGitCredentialId(Long value) { gitCredentialId=value; }
    @NotBlank(message="请填写 Git 仓库地址") @Size(max=2048)
    private String remoteUrl;
    @Size(max=256) @Pattern(regexp="[^\\r\\n\\x00]*",message="Git 账号不能包含换行或空字符")
    private String gitUsername;
    @JsonProperty(access=JsonProperty.Access.WRITE_ONLY)
    private String gitPassword;
    private Long projectId;
    private boolean useStoredCredentials;
    public String getRemoteUrl() {return remoteUrl;}
    public void setRemoteUrl(String value) {remoteUrl=value;}
    public String getGitUsername() {return gitUsername;}
    public void setGitUsername(String value) {gitUsername=value;}
    public String getGitPassword() {return gitPassword;}
    public void setGitPassword(String value) {gitPassword=value;}
    public Long getProjectId() {return projectId;}
    public void setProjectId(Long value) {projectId=value;}
    public boolean isUseStoredCredentials() {return useStoredCredentials;}
    public void setUseStoredCredentials(boolean value) {useStoredCredentials=value;}
    @JsonIgnore @AssertTrue(message="Git 密码或令牌最多4000个字符，不能包含换行或空字符")
    public boolean isGitPasswordValid() {return gitPassword==null || (gitPassword.length()<=4000 && gitPassword.indexOf('\r')<0 && gitPassword.indexOf('\n')<0 && gitPassword.indexOf(0)<0);}
}
