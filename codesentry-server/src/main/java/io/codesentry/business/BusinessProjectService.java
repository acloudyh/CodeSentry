package io.codesentry.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.codesentry.common.error.BusinessException;
import io.codesentry.project.mapper.ScanProjectMapper;
import io.codesentry.project.model.ProjectSourceType;
import io.codesentry.project.model.ScanProject;
import io.codesentry.scan.dto.CreateScanRunRequest;
import io.codesentry.scan.model.*;
import io.codesentry.scan.service.ScanRunService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理业务项目、资产归属和项目扫描轮次，并以只读映射兼容原资产历史。 */
@Service
public class BusinessProjectService {
  private final JdbcTemplate db;
  private final ScanProjectMapper assets;
  private final ScanRunService scans;
  private final ObjectMapper json;

  /** 注入项目存储、资产查询及原扫描服务，复用已有扫描与历史标识。 */
  public BusinessProjectService(
      JdbcTemplate db, ScanProjectMapper assets, ScanRunService scans, ObjectMapper json) {
    this.db = db;
    this.assets = assets;
    this.scans = scans;
    this.json = json;
  }

  /** 构造参数校验失败响应，向调用方返回可直接展示的业务原因。 */
  public static BusinessException bad(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, message);
  }

  /** 构造资源状态冲突响应，阻止不符合当前状态的操作。 */
  public static BusinessException conflict(String message) {
    return new BusinessException(HttpStatus.CONFLICT, message);
  }

  /** 按业务项目 ID 读取项目；不存在时返回 404，避免后续查询落到空范围。 */
  public BusinessProject require(Long id) {
    List<BusinessProject> list =
        db.query(
            "SELECT * FROM business_project WHERE id=?",
            new BeanPropertyRowMapper<>(BusinessProject.class),
            id);
    if (list.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND, "业务项目不存在");
    return list.get(0);
  }

  /** 锁定项目行，串行化资产归属、轮次创建和归档等变更。 */
  private void lock(Long id) {
    if (db.queryForList("SELECT id FROM business_project WHERE id=? FOR UPDATE", Long.class, id)
        .isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND, "业务项目不存在");
  }

  /** 列出业务项目及代码/JAR 数量，并解析其时间上最新的真实或历史扫描范围。 */
  public List<BusinessProject> list() {
    List<BusinessProject> list =
        db.query(
            "SELECT b.*, (SELECT COUNT(*) FROM business_project_asset a JOIN scan_project p ON p.id=a.asset_id WHERE a.business_project_id=b.id AND p.enabled=1 AND p.source_type!='JAR_UPLOAD') repository_count, (SELECT COUNT(*) FROM business_project_asset a JOIN scan_project p ON p.id=a.asset_id WHERE a.business_project_id=b.id AND p.enabled=1 AND p.source_type='JAR_UPLOAD') jar_count FROM business_project b ORDER BY b.id DESC",
            new BeanPropertyRowMapper<>(BusinessProject.class));
    for (BusinessProject b : list) {
      Long last = latestRoundId(b.id);
      b.latestRound = last == null ? null : round(b.id, last);
    }
    return list;
  }

  /** 校验名称与备注后新建或编辑项目，不修改资产及历史扫描。 */
  @Transactional
  public BusinessProject save(Long id, String name, String description) {
    if (name == null
        || name.trim().isEmpty()
        || name.trim().length() > 128
        || (description != null && description.length() > 2000))
      throw bad("项目名称不能为空且最多128字，描述最多2000字");
    if (id == null) {
      db.update(
          "INSERT INTO business_project(name,description,created_at,updated_at) VALUES(?,?,NOW(3),NOW(3))",
          name.trim(),
          description);
      id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    } else {
      lock(id);
      db.update(
          "UPDATE business_project SET name=?,description=?,updated_at=NOW(3) WHERE id=?",
          name.trim(),
          description,
          id);
    }
    return require(id);
  }

  /** 仅删除既无关联资产又无项目扫描历史的业务项目。 */
  @Transactional
  public void delete(Long id) {
    lock(id);
    if (db.queryForObject(
                "SELECT COUNT(*) FROM business_project_asset WHERE business_project_id=?",
                Long.class,
                id)
            > 0
        || db.queryForObject(
                "SELECT COUNT(*) FROM project_scan_round WHERE business_project_id=?",
                Long.class,
                id)
            > 0) throw conflict("仅可删除无资产、无扫描历史的项目，请先解除资产归属");
    db.update("DELETE FROM business_project WHERE id=?", id);
  }

  /** 读取项目当前有效资产，排除已停用项。 */
  public List<ScanProject> assets(Long id) {
    require(id);
    return db.query(
        "SELECT p.*, a.business_project_id FROM scan_project p JOIN business_project_asset a ON a.asset_id=p.id WHERE a.business_project_id=? AND p.enabled=1 ORDER BY p.id",
        new BeanPropertyRowMapper<>(ScanProject.class),
        id);
  }

  /** 在项目与资产行锁保护下建立唯一归属；已有其他归属时拒绝覆盖。 */
  @Transactional
  public void attach(Long id, Long assetId) {
    lock(id);
    ScanProject asset = assets.findByIdForUpdate(assetId);
    if (asset == null || !Boolean.TRUE.equals(asset.getEnabled())) throw bad("扫描资产不存在或已移除");
    List<Long> owners =
        db.queryForList(
            "SELECT business_project_id FROM business_project_asset WHERE asset_id=?",
            Long.class,
            assetId);
    if (!owners.isEmpty()) {
      if (!owners.get(0).equals(id)) throw conflict("资产已归属其他项目，请先解除原归属");
      return;
    }
    db.update(
        "INSERT INTO business_project_asset(asset_id,business_project_id) VALUES(?,?)",
        assetId,
        id);
  }

  /** 仅解除当前项目归属，保留原资产、扫描、台账和报告。 */
  @Transactional
  public void detach(Long id, Long assetId) {
    lock(id);
    assets.findByIdForUpdate(assetId);
    db.update(
        "DELETE FROM business_project_asset WHERE business_project_id=? AND asset_id=?",
        id,
        assetId);
  }

  /**
   * Negative view IDs identify existing standalone scans; no synthetic database rounds are
   * inserted.
   */
  // 正数是项目轮次，负数只映射原资产扫描；排除已关联子任务，避免同次扫描重复出现。
  private static final String TIMELINE =
      "SELECT id,created_at FROM project_scan_round WHERE business_project_id=? UNION ALL "
          + "SELECT -s.id AS id,s.created_at FROM scan_run s JOIN business_project_asset a ON a.asset_id=s.project_id "
          + "JOIN scan_project p ON p.id=a.asset_id WHERE a.business_project_id=? AND p.enabled=1 "
          + "AND NOT EXISTS(SELECT 1 FROM project_scan_item i WHERE i.run_id=s.id)";

  /** 从统一时间线选取最新扫描范围；负数标识原资产历史，不新增项目轮次。 */
  public Long latestRoundId(Long id) {
    List<Long> ids =
        db.queryForList(
            "SELECT id FROM (" + TIMELINE + ") timeline ORDER BY created_at DESC,id DESC LIMIT 1",
            Long.class,
            id,
            id);
    return ids.isEmpty() ? null : ids.get(0);
  }

  /** 按实际扫描时间分页读取项目轮次与原资产历史，返回全量匹配总数。 */
  public Map<String, Object> rounds(Long id, int page, int size) {
    require(id);
    io.codesentry.common.api.PageBounds.require(page, size);
    List<Long> ids =
        db.queryForList(
            "SELECT id FROM ("
                + TIMELINE
                + ") timeline ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            Long.class,
            id,
            id,
            size,
            (long) (page - 1) * size);
    return Map.of(
        "items",
        ids.stream().map(r -> round(id, r)).collect(Collectors.toList()),
        "total",
        db.queryForObject("SELECT COUNT(*) FROM (" + TIMELINE + ") timeline", Long.class, id, id),
        "page",
        page,
        "pageSize",
        size);
  }

  /** 将负数视图 ID 解析为原扫描，校验当前归属并保留原问题及报告来源。 */
  private ProjectScanRound assetHistory(Long projectId, Long viewId) {
    BusinessProject project = require(projectId);
    if (viewId == Long.MIN_VALUE) throw new BusinessException(HttpStatus.NOT_FOUND, "历史扫描不存在");
    long runId = -viewId;
    List<ScanProject> owners =
        db.query(
            "SELECT p.* FROM scan_project p JOIN business_project_asset a ON a.asset_id=p.id JOIN scan_run s ON s.project_id=p.id WHERE a.business_project_id=? AND p.enabled=1 AND s.id=? AND NOT EXISTS(SELECT 1 FROM project_scan_item i WHERE i.run_id=s.id)",
            new BeanPropertyRowMapper<>(ScanProject.class),
            projectId,
            runId);
    if (owners.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND, "历史扫描不属于当前项目或已归档为项目轮次");
    ScanProject asset = owners.get(0);
    ScanRun run = scans.requireById(runId);
    ProjectScanRound r = new ProjectScanRound();
    r.id = viewId;
    r.assetHistory = true;
    r.businessProjectId = projectId;
    r.projectName = project.name;
    r.createdAt = run.getCreatedAt();
    r.networkMode = run.getNetworkMode().name();
    r.ruleProfile = run.getRuleProfile();
    r.fullScope = false;
    Map<Long, ScanProject> owned =
        assets(projectId).stream().collect(Collectors.toMap(ScanProject::getId, x -> x));
    r.assetCount = owned.size();
    try {
      r.unselectedAssets =
          json.readValue(
              unselectedJson(owned, Set.of(asset.getId())),
              new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    ProjectScanItem item = new ProjectScanItem();
    item.id = runId;
    item.roundId = viewId;
    item.assetId = asset.getId();
    item.assetName = asset.getDisplayName();
    item.sourceType = asset.getSourceType().name();
    item.targetJavaVersion = asset.getTargetJavaVersion();
    item.applicationType = asset.getApplicationType();
    item.targetNodeVersion = asset.getTargetNodeVersion();
    item.workspaceMode = run.getWorkspaceMode().name();
    item.scanRef = run.getScanRef();
    item.artifactPath = run.getArtifactPath();
    item.runId = runId;
    r.items = List.of(item);
    return hydrate(r);
  }

  /** 校验轮次所属项目；历史视图与真实项目轮次分别解析。 */
  public ProjectScanRound round(Long projectId, Long roundId) {
    if (roundId < 0) return assetHistory(projectId, roundId);
    List<ProjectScanRound> found =
        db.query(
            "SELECT * FROM project_scan_round WHERE id=? AND business_project_id=?",
            new BeanPropertyRowMapper<>(ProjectScanRound.class),
            roundId,
            projectId);
    if (found.isEmpty()) throw new BusinessException(HttpStatus.NOT_FOUND, "项目扫描轮次不存在");
    ProjectScanRound r = found.get(0);
    try {
      r.unselectedAssets =
          json.readValue(
              r.unselectedJson,
              new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("项目扫描范围读取失败", e);
    }
    r.items =
        db.query(
            "SELECT * FROM project_scan_item WHERE round_id=? ORDER BY id",
            new BeanPropertyRowMapper<>(ProjectScanItem.class),
            roundId);
    return hydrate(r);
  }

  /** 根据各子任务当前状态汇总完成、失败和取消数量，不用旧结果补齐失败项。 */
  private ProjectScanRound hydrate(ProjectScanRound r) {
    int active = 0, cancelled = 0;
    for (ProjectScanItem item : r.items) {
      item.run = item.runId == null ? null : scans.requireById(item.runId);
      item.status = item.run == null ? "FAILED" : item.run.getStatus().name();
      if (item.run != null && item.run.getErrorMessage() != null)
        item.errorMessage = item.run.getErrorMessage();
      if ("COMPLETED".equals(item.status)) r.completedCount++;
      else if (List.of("PENDING", "PREPARING", "RUNNING").contains(item.status)) active++;
      else {
        r.failedCount++;
        if ("CANCELLED".equals(item.status)) cancelled++;
      }
    }
    r.status =
        active > 0
            ? "RUNNING"
            : r.completedCount == r.items.size()
                ? "COMPLETED"
                : cancelled == r.items.size()
                    ? "CANCELLED"
                    : r.completedCount > 0
                            || r.items.stream().anyMatch(i -> "PARTIAL".equals(i.status))
                        ? "PARTIAL"
                        : "FAILED";
    return r;
  }

  /** 以请求键幂等创建项目扫描轮次；一项资产对应一个原有扫描任务。 */
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public ProjectScanRound start(Long id, ProjectScanRequest request) {
    return createRound(id, request, null);
  }

  /** 校验资产范围与配置并固化轮次快照，同事务创建子任务关联。 */
  private ProjectScanRound createRound(Long id, ProjectScanRequest request, Long retryOf) {
    // 锁住项目后校验请求摘要：相同幂等键只复用同配置，防止双击或重试创建重复子任务。
    lock(id);
    String hash = hash(request, retryOf);
    List<ProjectScanRound> existing =
        db.query(
            "SELECT * FROM project_scan_round WHERE business_project_id=? AND request_key=?",
            new BeanPropertyRowMapper<>(ProjectScanRound.class),
            id,
            request.requestKey);
    if (!existing.isEmpty()) {
      if (!existing.get(0).requestHash.equals(hash)) throw conflict("同一请求标识不能用于不同扫描配置");
      return round(id, existing.get(0).id);
    }
    if (request.assets == null || request.assets.isEmpty() || request.networkMode == null)
      throw bad("请选择扫描资产及扫描方式");
    Map<Long, ScanProject> owned =
        assets(id).stream().collect(Collectors.toMap(ScanProject::getId, a -> a));
    Set<Long> selected = new HashSet<>();
    for (ProjectScanRequest.Target target : request.assets) {
      if (target.assetId == null
          || !selected.add(target.assetId)
          || !owned.containsKey(target.assetId)) throw bad("扫描资产重复或不属于当前项目");
      ScanProject asset = assets.findByIdForUpdate(target.assetId);
      if (asset == null || !Boolean.TRUE.equals(asset.getEnabled())) throw bad("资产已移除，请刷新项目");
      if (target.workspaceMode == null) throw bad("请选择扫描目标");
    }
    if (request.fullScope && selected.size() != owned.size()) throw bad("全项目扫描必须包含全部当前资产，请刷新项目");
    BusinessProject project = require(id);
    // 固化本轮范围和未选资产，后续归属变化不能悄悄改变历史轮次的覆盖口径。
    db.update(
        "INSERT INTO project_scan_round(business_project_id,project_name,request_key,request_hash,full_scope,asset_count,unselected_json,refresh_remote,network_mode,rule_profile,retry_of,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,NOW(3))",
        id,
        project.name,
        request.requestKey,
        hash,
        request.fullScope,
        owned.size(),
        unselectedJson(owned, selected),
        request.refreshRemote && request.networkMode == NetworkMode.ONLINE,
        request.networkMode.name(),
        request.ruleProfile,
        retryOf);
    Long roundId = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    for (ProjectScanRequest.Target target : request.assets) {
      ScanProject asset = owned.get(target.assetId);
      CreateScanRunRequest child = new CreateScanRunRequest();
      child.setProjectId(asset.getId());
      child.setScanMode(ScanMode.FULL);
      child.setNetworkMode(request.networkMode);
      child.setRuleProfile(request.ruleProfile);
      child.setWorkspaceMode(target.workspaceMode);
      child.setScanRef(target.scanRef);
      child.setRefreshRemote(request.networkMode == NetworkMode.ONLINE && request.refreshRemote);
      Long runId = null;
      String error = null;
      try {
        runId = scans.start(child).getId();
      } catch (BusinessException e) {
        error = e.getMessage();
      }
      db.update(
          "INSERT INTO project_scan_item(round_id,asset_id,asset_name,source_type,target_java_version,application_type,target_node_version,workspace_mode,scan_ref,artifact_path,run_id,error_message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
          roundId,
          asset.getId(),
          asset.getDisplayName(),
          asset.getSourceType().name(),
          asset.getTargetJavaVersion(),
          asset.getApplicationType().name(),
          asset.getTargetNodeVersion(),
          target.workspaceMode.name(),
          child.getScanRef(),
          asset.getSourceType() == ProjectSourceType.JAR_UPLOAD ? asset.getLocalPath() : null,
          runId,
          error);
    }
    return round(id, roundId);
  }

  /** 仅对原轮次失败项创建关联的新轮次，不覆盖原始记录。 */
  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public ProjectScanRound retry(Long id, Long source, String requestKey) {
    ProjectScanRound previous = round(id, source);
    if (previous.assetHistory) throw bad("请从原扫描记录重新发起资产扫描，历史记录不能作为项目轮次重试");
    ProjectScanRequest request = new ProjectScanRequest();
    request.requestKey = requestKey;
    request.networkMode = NetworkMode.valueOf(previous.networkMode);
    request.ruleProfile = previous.ruleProfile;
    request.refreshRemote = previous.refreshRemote;
    request.assets =
        previous.items.stream()
            .filter(i -> List.of("FAILED", "PARTIAL", "CANCELLED").contains(i.status))
            .map(
                i -> {
                  ProjectScanRequest.Target t = new ProjectScanRequest.Target();
                  t.assetId = i.assetId;
                  t.workspaceMode = WorkspaceMode.valueOf(i.workspaceMode);
                  t.scanRef = i.scanRef;
                  return t;
                })
            .collect(Collectors.toList());
    return createRound(id, request, source);
  }

  /** 取消当前轮次中尚未结束的子任务，保留已执行证据及原轮次。 */
  @Transactional
  public ProjectScanRound cancel(Long id, Long roundId) {
    lock(id);
    ProjectScanRound r = round(id, roundId);
    if (r.assetHistory) throw bad("请从原扫描记录停止资产扫描");
    for (ProjectScanItem item : r.items)
      if (List.of("PENDING", "PREPARING", "RUNNING").contains(item.status)) {
        try {
          scans.cancel(item.runId);
        } catch (BusinessException e) {
          if (e.getStatus() != HttpStatus.CONFLICT) throw e;
        }
      }
    return round(id, roundId);
  }

  /** 保存本轮未选择资产的身份快照，用于报告覆盖范围说明。 */
  private String unselectedJson(Map<Long, ScanProject> owned, Set<Long> selected) {
    try {
      return json.writeValueAsString(
          owned.values().stream()
              .filter(a -> !selected.contains(a.getId()))
              .map(
                  a ->
                      Map.of(
                          "assetId",
                          a.getId(),
                          "assetName",
                          a.getDisplayName(),
                          "sourceType",
                          a.getSourceType().name()))
              .collect(Collectors.toList()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** 对扫描配置和重试来源计算摘要，防止同一幂等键被用于不同请求。 */
  private String hash(ProjectScanRequest request, Long retryOf) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (json.writeValueAsString(request) + ":" + retryOf)
                      .getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : digest) out.append(String.format("%02x", b));
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
