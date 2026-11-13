package io.codesentry.scan.engine;

import java.util.List;

public interface ScannerAdapter {

    String name();

    boolean enabled();

    boolean available();

    /** 最近一次可用性检测失败的安全说明，供任务详情展示。 */
    default String unavailabilityReason() { return "扫描器不可用或版本检测失败"; }

    List<NormalizedFinding> scan(ScanContext context);

    default String reportExtension() {
        return "json";
    }
}

