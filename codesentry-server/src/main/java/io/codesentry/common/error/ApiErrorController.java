package io.codesentry.common.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 为容器 404 等未进入 ControllerAdvice 的错误提供同一安全协议，不回显容器异常属性。 */
@RestController
public class ApiErrorController implements ErrorController {
  @RequestMapping(
      value = "/error",
      method = {
        org.springframework.web.bind.annotation.RequestMethod.GET,
        org.springframework.web.bind.annotation.RequestMethod.POST,
        org.springframework.web.bind.annotation.RequestMethod.PUT,
        org.springframework.web.bind.annotation.RequestMethod.PATCH,
        org.springframework.web.bind.annotation.RequestMethod.DELETE,
        org.springframework.web.bind.annotation.RequestMethod.HEAD,
        org.springframework.web.bind.annotation.RequestMethod.OPTIONS
      })
  public ResponseEntity<ApiProblem> error(HttpServletRequest request) {
    Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    HttpStatus status =
        attribute instanceof Integer ? HttpStatus.resolve((Integer) attribute) : null;
    if (status == null || !status.isError()) status = HttpStatus.INTERNAL_SERVER_ERROR;
    ApiProblem body =
        new ApiProblem(
            status,
            "HTTP_" + status.value(),
            status == HttpStatus.NOT_FOUND ? "请求的资源不存在" : "请求处理失败，请凭追踪编号联系管理员",
            "/api",
            List.of());
    return ResponseEntity.status(status)
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .header(io.codesentry.common.http.RequestTrace.HEADER, body.traceId)
        .body(body);
  }
}
