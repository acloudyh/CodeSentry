package io.codesentry.common.api;

import io.codesentry.common.error.BusinessException;
import org.springframework.http.HttpStatus;

/** 服务层共用分页边界；拒绝非法范围，避免静默截断掩盖前端错误和大页拖慢服务。 */
public final class PageBounds {
  private PageBounds() {}

  public static void require(int page, int pageSize) {
    if (page < 1 || pageSize < 1 || pageSize > 100) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "页码必须大于 0，每页条数必须在 1 至 100 之间");
    }
  }
}
