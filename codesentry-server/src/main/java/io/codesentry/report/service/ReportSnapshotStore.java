package io.codesentry.report.service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.finding.model.FindingView;
import io.codesentry.report.mapper.ReportSnapshotMapper;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public class ReportSnapshotStore {
    private final ReportSnapshotMapper mapper;
    private final ObjectMapper json;
    public ReportSnapshotStore(ReportSnapshotMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }
    public List<FindingView> read(Long runId) {
        String value = mapper.find(runId);
        if (value == null) return null;
        try { return json.readValue(value, new TypeReference<List<FindingView>>() {}); }
        catch (Exception e) { throw new IllegalStateException("报告归档读取失败", e); }
    }
    public List<FindingView> freeze(Long runId, List<FindingView> findings) {
        try { mapper.insert(runId, json.writeValueAsString(findings)); }
        catch (Exception e) { throw new IllegalStateException("报告归档保存失败", e); }
        return read(runId);
    }
}
