package io.codesentry.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将所有接口失败转换为稳定的 Problem；底层异常原文只作为内部分类信息，不发送到页面。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiProblem> handleBusinessException(
      BusinessException exception, HttpServletRequest request) {
    HttpStatus status = exception.getStatus();
    String detail =
        status.is5xxServerError()
            ? "服务处理失败，请凭追踪编号联系管理员"
            : SafeErrorMessage.clean(exception.getMessage(), "请求处理失败，请检查输入或联系管理员");
    return problem(status, exception.getCode(), detail, request, List.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiProblem> handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    List<ApiProblem.FieldViolation> errors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(
                error ->
                    new ApiProblem.FieldViolation(
                        error.getField(),
                        SafeErrorMessage.clean(error.getDefaultMessage(), "字段值不合法")))
            .collect(Collectors.toList());
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", request, errors);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiProblem> handleConstraint(
      ConstraintViolationException exception, HttpServletRequest request) {
    List<ApiProblem.FieldViolation> errors =
        exception.getConstraintViolations().stream()
            .map(
                error ->
                    new ApiProblem.FieldViolation(
                        error.getPropertyPath().toString(),
                        SafeErrorMessage.clean(error.getMessage(), "字段值不合法")))
            .collect(Collectors.toList());
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", request, errors);
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<ApiProblem> handleInvalidBody(
      Exception exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_BODY", "请求内容格式错误或包含不支持的字段", request, List.of());
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<ApiProblem> handleDuplicate(
      DuplicateKeyException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", "名称已存在，请使用其他名称", request, List.of());
  }

  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiProblem> handleUploadTooLarge(
      Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "UPLOAD_TOO_LARGE",
        "上传文件过大，单个 JAR 最大支持 2 GB（2048 MB）",
        request,
        List.of());
  }

  @ExceptionHandler({
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class,
    org.springframework.web.multipart.support.MissingServletRequestPartException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  public ResponseEntity<ApiProblem> handleMissingParameter(
      Exception exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "请求参数缺失或格式不合法", request, List.of());
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiProblem> handleMethod(Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "请求方法不受支持", request, List.of());
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiProblem> handleMediaType(
      Exception exception, HttpServletRequest request) {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "请求内容类型不受支持",
        request,
        List.of());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiProblem> handleUnexpected(
      Exception exception, HttpServletRequest request) {
    // 异常消息可能含 SQL、工具命令和凭据，只记录异常类型及 MDC 中的追踪编号。
    LOG.error(
        "request_failed exceptionType={} traceId={}",
        exception.getClass().getName(),
        io.codesentry.common.http.RequestTrace.current());
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "服务处理失败，请凭追踪编号联系管理员",
        request,
        List.of());
  }

  private ResponseEntity<ApiProblem> problem(
      HttpStatus status,
      String code,
      String detail,
      HttpServletRequest request,
      List<ApiProblem.FieldViolation> errors) {
    Object route =
        request.getAttribute(
            org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    ApiProblem body =
        new ApiProblem(status, code, detail, route == null ? "/api" : route.toString(), errors);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status)
            .contentType(MediaType.parseMediaType("application/problem+json"))
            .header(io.codesentry.common.http.RequestTrace.HEADER, body.traceId);
    if (status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE)
      response.header("Retry-After", "30");
    return response.body(body);
  }
}
