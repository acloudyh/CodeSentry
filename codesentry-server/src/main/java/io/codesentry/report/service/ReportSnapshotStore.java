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
    /** 归档清单只提取顶层问题 ID，跳过大段源码和审核文本；null 区别于已归档空清单。 */
    public List<Long> readIds(Long runId) {
        String value = mapper.find(runId);
        if (value == null) return null;
        List<Long> ids = new java.util.ArrayList<>();
        try (com.fasterxml.jackson.core.JsonParser parser = json.getFactory().createParser(value)) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY) throw new IllegalStateException("归档格式错误");
            while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                if (parser.currentToken() == null) throw new IllegalStateException("归档内容不完整");
                if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) throw new IllegalStateException("归档格式错误");
                while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                    if (parser.currentToken() == null) throw new IllegalStateException("归档内容不完整");
                    String field = parser.currentName(); parser.nextToken();
                    if ("id".equals(field) && parser.currentToken().isNumeric()) ids.add(parser.getLongValue());
                    else parser.skipChildren();
                }
            }
            return ids;
        } catch (Exception e) { throw new IllegalStateException("报告归档读取失败", e); }
    }

    /** 列表统计仅读取归档的身份、变化状态和依赖分组字段，不反序列化长证据。 */
    public List<FindingView> readSummary(Long runId) {
        String value = mapper.find(runId);
        if (value == null) return null;
        List<FindingView> result = new java.util.ArrayList<>();
        try (com.fasterxml.jackson.core.JsonParser parser = json.getFactory().createParser(value)) {
            if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY) throw new IllegalStateException("归档格式错误");
            while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                if (parser.currentToken() != com.fasterxml.jackson.core.JsonToken.START_OBJECT) throw new IllegalStateException("归档内容不完整");
                FindingView finding = new FindingView();
                while (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                    if (parser.currentToken() == null) throw new IllegalStateException("归档内容不完整");
                    String field = parser.currentName(); parser.nextToken();
                    switch (field) {
                        case "id": finding.setId(parser.getValueAsLong()); break;
                        case "scanner": finding.setScanner(parser.getValueAsString()); break;
                        case "category": finding.setCategory(parser.getValueAsString()); break;
                        case "filePath": finding.setFilePath(parser.getValueAsString()); break;
                        case "resultStatus": finding.setResultStatus(parser.getValueAsString()); break;
                        case "codeSnippet":
                            String snippet = parser.getValueAsString();
                            finding.setCodeSnippet(snippet == null ? null : snippet.split("\n",2)[0]); break;
                        default: parser.skipChildren();
                    }
                }
                result.add(finding);
            }
            return result;
        } catch (Exception e) { throw new IllegalStateException("报告归档读取失败", e); }
    }

    public List<FindingView> freeze(Long runId, List<FindingView> findings) {
        try { mapper.insert(runId, json.writeValueAsString(findings)); }
        catch (Exception e) { throw new IllegalStateException("报告归档保存失败", e); }
        return read(runId);
    }
}
