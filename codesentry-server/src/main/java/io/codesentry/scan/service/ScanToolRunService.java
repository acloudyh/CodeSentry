package io.codesentry.scan.service;

import io.codesentry.process.CommandTrace;
import io.codesentry.scan.mapper.ScanToolRunMapper;
import io.codesentry.scan.model.ScanToolRun;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ScanToolRunService {

    private static final int MAX_LOG_LENGTH = 200_000;

    private final ScanToolRunMapper mapper;

    public ScanToolRunService(ScanToolRunMapper mapper) {
        this.mapper = mapper;
    }

    public ScanToolRun start(Long scanRunId, String toolName, String phase) {
        ScanToolRun toolRun = new ScanToolRun();
        toolRun.setScanRunId(scanRunId);
        toolRun.setToolName(toolName);
        toolRun.setPhase(phase);
        toolRun.setStatus("RUNNING");
        toolRun.setStartedAt(LocalDateTime.now());
        mapper.insert(toolRun);
        return toolRun;
    }

    public void skipped(Long scanRunId, String toolName, String reason) {
        ScanToolRun toolRun = new ScanToolRun();
        toolRun.setScanRunId(scanRunId);
        toolRun.setToolName(toolName);
        toolRun.setPhase("availability");
        toolRun.setStatus("SKIPPED");
        toolRun.setErrorMessage(reason);
        toolRun.setOutputLog(reason);
        toolRun.setStartedAt(LocalDateTime.now());
        toolRun.setFinishedAt(toolRun.getStartedAt());
        mapper.insert(toolRun);
        mapper.finish(toolRun);
    }

    public void finishSuccess(ScanToolRun toolRun, CommandTrace trace, Path report, int findingCount) {
        fillFromTrace(toolRun, trace);
        toolRun.setStatus("COMPLETED");
        toolRun.setRawReportPath(report == null ? null : report.toAbsolutePath().toString());
        toolRun.setOutputLog(appendSummary(toolRun.getOutputLog(), "扫描完成，发现 " + findingCount + " 个问题。"));
        toolRun.setFinishedAt(LocalDateTime.now());
        mapper.finish(toolRun);
    }

    public void updateRunningLog(ScanToolRun toolRun, CommandTrace trace) {
        fillFromTrace(toolRun, trace);
        mapper.updateRunningLog(toolRun);
    }

    public void finishFailure(ScanToolRun toolRun, CommandTrace trace, Path report, String message) {
        fillFromTrace(toolRun, trace);
        toolRun.setStatus(Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED");
        toolRun.setRawReportPath(report == null ? null : report.toAbsolutePath().toString());
        toolRun.setErrorMessage(message);
        toolRun.setOutputLog(appendSummary(toolRun.getOutputLog(),
                (Thread.currentThread().isInterrupted() ? "扫描已停止：" : "扫描失败：") + message));
        toolRun.setFinishedAt(LocalDateTime.now());
        mapper.finish(toolRun);
    }

    public List<ScanToolRun> listByScanRun(Long scanRunId) {
        return mapper.findByScanRunId(scanRunId);
    }

    public void cancelRunning(Long runId) { mapper.cancelRunning(runId); }

    private void fillFromTrace(ScanToolRun toolRun, CommandTrace trace) {
        if (trace == null) {
            return;
        }
        toolRun.setExitCode(trace.getExitCode());
        toolRun.setCommandLine(trace.getCommandLine());
        toolRun.setWorkingDirectory(trace.getWorkingDirectory());
        toolRun.setOutputLog(limit(trace.getOutput()));
    }

    private String appendSummary(String output, String summary) {
        String value = output == null || output.isBlank() ? summary : output + "\n\n" + summary;
        return limit(value);
    }

    private String limit(String value) {
        if (value == null || value.length() <= MAX_LOG_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LOG_LENGTH) + "\n\n[CodeSentry] 日志过长，已截断。";
    }
}
