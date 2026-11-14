package io.codesentry.finding.service;

import io.codesentry.common.error.BusinessException;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 精确规则分类维护；未知规则先待分类，不按扫描器名称整批绕过安全审核。 */
@Service
public class FindingRuleCatalog implements ApplicationRunner {
  private final JdbcTemplate db;

  public FindingRuleCatalog(JdbcTemplate db) {
    this.db = db;
  }

  public void run(ApplicationArguments args) {
    reload();
  }

  private void reload() {
    Map<String, String> rules = new HashMap<>();
    db.queryForList("SELECT rule_id,issue_type FROM finding_rule_classification")
        .forEach(r -> rules.put((String) r.get("rule_id"), (String) r.get("issue_type")));
    FindingClassification.configure(rules);
  }

  public List<Map<String, Object>> list() {
    return db.queryForList(
        "SELECT f.rule_id ruleId,COUNT(*) findingCount,MAX(c.reason) reason,("
            + FindingClassification.sql("f")
            + ") issueType FROM scan_finding f LEFT JOIN finding_rule_classification c ON c.rule_id=f.rule_id GROUP BY f.rule_id,f.category ORDER BY COUNT(*) DESC LIMIT 500");
  }

  public synchronized void save(String rule, String type, String reason) {
    if (rule == null
        || !rule.matches("[A-Za-z0-9_.:/-]{1,300}")
        || !Set.of("QUALITY", "SECURITY", "AUDIT").contains(Objects.toString(type, ""))
        || reason == null
        || reason.isBlank()
        || reason.length() > 1000)
      throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入精确规则 ID、有效分类及分类依据");
    db.update(
        "INSERT INTO finding_rule_classification(rule_id,issue_type,reason,updated_at) VALUES(?,?,?,NOW(3)) ON DUPLICATE KEY UPDATE issue_type=VALUES(issue_type),reason=VALUES(reason),updated_at=NOW(3)",
        rule,
        type,
        reason.trim());
    reload();
  }
}
