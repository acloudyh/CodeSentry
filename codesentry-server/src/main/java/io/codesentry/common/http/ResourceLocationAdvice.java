package io.codesentry.common.http;

import io.codesentry.common.api.ApiResponse;
import io.codesentry.project.model.ScanProject;
import io.codesentry.scan.model.ScanRun;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 为现有 201/202 接口补齐资源或任务位置，不改变 v1 成功响应形状。 */
@ControllerAdvice
public class ResourceLocationAdvice implements ResponseBodyAdvice<ApiResponse<?>> {
  @Override
  public boolean supports(
      MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) {
    ResponseStatus status = method.getMethodAnnotation(ResponseStatus.class);
    return status != null
        && (status.value().value() == 201 || status.value().value() == 202)
        && ApiResponse.class.isAssignableFrom(method.getParameterType());
  }

  @Override
  public ApiResponse<?> beforeBodyWrite(
      ApiResponse<?> body,
      MethodParameter method,
      MediaType type,
      Class<? extends HttpMessageConverter<?>> converter,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body != null) {
      Object resource = body.getData();
      if (resource instanceof ScanProject)
        response
            .getHeaders()
            .set("Location", "/api/v1/projects/" + ((ScanProject) resource).getId());
      if (resource instanceof ScanRun)
        response.getHeaders().set("Location", "/api/v1/scan-runs/" + ((ScanRun) resource).getId());
    }
    return body;
  }
}
