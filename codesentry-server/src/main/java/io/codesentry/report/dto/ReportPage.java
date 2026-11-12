package io.codesentry.report.dto;

import java.util.List;

/** 单资产整改报告分页结果：total 为筛选后的全量总数，items 仅包含当前页。 */
public class ReportPage {
    private final List<RemediationReportSummary> items;
    private final long total;
    private final int page;
    private final int pageSize;

    /** 保存归一化后的页码、每页条数与完整匹配数量。 */
    public ReportPage(List<RemediationReportSummary> items,long total,int page,int pageSize) {
        this.items=items;this.total=total;this.page=page;this.pageSize=pageSize;
    }
    /** 当前页报告摘要，只为本页计算采纳与合并统计。 */
    public List<RemediationReportSummary> getItems() { return items; }
    /** 当前筛选范围下全部报告总数，不限制为最近 200 份。 */
    public long getTotal() { return total; }
    /** 服务端校验并修正后的实际页码，从 1 开始。 */
    public int getPage() { return page; }
    /** 服务端归一化后的单页条数，范围为 1 至 100。 */
    public int getPageSize() { return pageSize; }
}
