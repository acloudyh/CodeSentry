package io.codesentry.git.credential;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/** 共享 Git 账号的写入参数；密码只接收，不序列化回响应。 */
public class GitCredentialRequest {
  @NotBlank
  @Size(max = 128)
  public String name;

  @NotBlank
  @Size(max = 2048)
  public String serverUrl;

  @NotBlank
  @Size(max = 256)
  @Pattern(regexp = "[^\\r\\n\\x00]*")
  public String username;

  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  public String password;

  @JsonIgnore
  @AssertTrue(message = "密码或令牌最多4000个字符，不能包含换行或空字符")
  public boolean isPasswordValid() {
    return password == null
        || (password.length() <= 4000
            && password.indexOf('\r') < 0
            && password.indexOf('\n') < 0
            && password.indexOf(0) < 0);
  }
}
