package io.codesentry.scan.dto;

import io.codesentry.scan.model.ScanRun;
import io.codesentry.scan.model.ScanRunStatus;
import io.codesentry.scan.model.ScanToolRun;

import java.util.List;

public class ScanRunDetail {

    private final ScanRun run;
    private final List<ScanToolRun> toolRuns;
    private final int progressPercent;

    public ScanRunDetail(ScanRun run, List<ScanToolRun> toolRuns) {
        this.run = run;
        this.toolRuns = toolRuns;
        this.progressPercent = calculateProgress(run, toolRuns);
    }

    public ScanRun getRun() {
        return run;
    }

    public List<ScanToolRun> getToolRuns() {
        return toolRuns;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    private int calculateProgress(ScanRun run, List<ScanToolRun> toolRuns) {
        if (run.getStatus() == ScanRunStatus.COMPLETED || run.getStatus() == ScanRunStatus.PARTIAL
                || run.getStatus() == ScanRunStatus.FAILED) {
            return 100;
        }
        if (toolRuns == null || toolRuns.isEmpty()) {
            return run.getStatus() == ScanRunStatus.PENDING ? 0 : 10;
        }
        long finished = toolRuns.stream()
                .filter(item -> !"RUNNING".equalsIgnoreCase(item.getStatus()))
                .count();
        int progress = (int) Math.round((finished * 100.0) / toolRuns.size());
        return Math.max(run.getStatus() == ScanRunStatus.RUNNING ? 15 : 5, Math.min(progress, 95));
    }
}
