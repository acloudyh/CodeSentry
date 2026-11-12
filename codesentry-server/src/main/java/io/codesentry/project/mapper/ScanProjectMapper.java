package io.codesentry.project.mapper;

import io.codesentry.project.model.ScanProject;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ScanProjectMapper {

    @Select("SELECT p.*, a.business_project_id FROM scan_project p LEFT JOIN business_project_asset a ON a.asset_id=p.id WHERE p.enabled = 1 ORDER BY p.created_at DESC")
    List<ScanProject> findAll();

    @Select("SELECT * FROM scan_project WHERE id = #{id}")
    ScanProject findById(@Param("id") Long id);

    @Select("SELECT * FROM scan_project WHERE id = #{id} FOR UPDATE")
    ScanProject findByIdForUpdate(@Param("id") Long id);

    @Insert("INSERT INTO scan_project(project_name, source_type, local_path, remote_url, managed_path, " +
            "default_branch, target_java_version, application_type, target_node_version, git_username, git_password_ciphertext, last_synced_at, last_sync_status, last_sync_message, repository_revision, enabled, created_at, updated_at) VALUES(#{projectName}, #{sourceType}, " +
            "#{localPath}, #{remoteUrl}, #{managedPath}, #{defaultBranch}, #{targetJavaVersion}, #{applicationType}, #{targetNodeVersion}, #{gitUsername}, #{gitPasswordCiphertext}, #{lastSyncedAt}, #{lastSyncStatus}, #{lastSyncMessage}, #{repositoryRevision}, #{enabled}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ScanProject project);

    @Update("UPDATE scan_project SET managed_path = #{managedPath}, default_branch = #{defaultBranch}, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int updateRepositoryState(ScanProject project);

    @Update("UPDATE scan_project SET project_name = #{projectName}, local_path = #{localPath}, " +
            "remote_url = #{remoteUrl}, managed_path = #{managedPath}, default_branch = #{defaultBranch}, target_java_version = #{targetJavaVersion}, application_type = #{applicationType}, target_node_version = #{targetNodeVersion}, git_username = #{gitUsername}, git_password_ciphertext = #{gitPasswordCiphertext}, last_synced_at = #{lastSyncedAt}, last_sync_status = #{lastSyncStatus}, last_sync_message = #{lastSyncMessage}, repository_revision = #{repositoryRevision}, " +
            "updated_at = #{updatedAt} WHERE id = #{id} AND enabled = 1")
    int update(ScanProject project);

    @Update("UPDATE scan_project SET last_synced_at=#{lastSyncedAt}, last_sync_status=#{lastSyncStatus}, last_sync_message=#{lastSyncMessage}, updated_at=NOW(3) WHERE id=#{id}")
    int updateSyncState(ScanProject project);

    @Select("SELECT id FROM scan_project WHERE enabled=1 AND source_type='GIT' AND last_sync_status='PENDING' ORDER BY updated_at LIMIT 100")
    List<Long> pendingRepositories();

    @Update("UPDATE scan_project SET managed_path=#{managedPath}, last_synced_at=#{lastSyncedAt}, last_sync_status=#{lastSyncStatus}, last_sync_message=#{lastSyncMessage}, updated_at=NOW(3) WHERE id=#{id} AND repository_revision=#{repositoryRevision} AND enabled=1")
    int finishRepositorySync(ScanProject project);

    @Update("UPDATE scan_project SET last_sync_status='FAILED', last_sync_message='服务重启，代码拉取已中断，请重试' WHERE source_type='GIT' AND last_sync_status='RUNNING'")
    int recoverRepositorySync();

    @Select("SELECT (SELECT COUNT(*) FROM scan_run WHERE project_id = #{id} " +
            "AND status IN ('PENDING','PREPARING','RUNNING')) + " +
            "(SELECT COUNT(*) FROM scan_ai_review_job j JOIN scan_run r ON r.id = j.scan_run_id " +
            "WHERE r.project_id = #{id} AND j.status IN ('PENDING','RUNNING'))")
    int countActiveTasks(@Param("id") Long id);

    @org.apache.ibatis.annotations.Delete("DELETE FROM business_project_asset WHERE asset_id = #{id}")
    int clearBusinessOwnership(@Param("id") Long id);

    @Update("UPDATE scan_project SET enabled = 0, updated_at = NOW(3) WHERE id = #{id}")
    int disableById(@Param("id") Long id);
}
