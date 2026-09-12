package io.codesentry.project.mapper;

import io.codesentry.project.model.ScanProject;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ScanProjectMapper {

    @Select("SELECT * FROM scan_project WHERE enabled = 1 ORDER BY created_at DESC")
    List<ScanProject> findAll();

    @Select("SELECT * FROM scan_project WHERE id = #{id}")
    ScanProject findById(@Param("id") Long id);

    @Insert("INSERT INTO scan_project(project_name, source_type, local_path, remote_url, managed_path, " +
            "default_branch, enabled, created_at, updated_at) VALUES(#{projectName}, #{sourceType}, " +
            "#{localPath}, #{remoteUrl}, #{managedPath}, #{defaultBranch}, #{enabled}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ScanProject project);

    @Update("UPDATE scan_project SET managed_path = #{managedPath}, default_branch = #{defaultBranch}, " +
            "updated_at = #{updatedAt} WHERE id = #{id}")
    int updateRepositoryState(ScanProject project);

    @Update("UPDATE scan_project SET enabled = 0, updated_at = NOW(3) WHERE id = #{id}")
    int disableById(@Param("id") Long id);
}
