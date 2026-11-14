package io.codesentry.ai.mapper;

import io.codesentry.ai.model.AiToolConfiguration;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiToolConfigurationMapper {
  @Select(
      "SELECT * FROM ai_tool_configuration ORDER BY default_tool DESC, updated_at DESC, id DESC")
  List<AiToolConfiguration> findAll();

  @Select("SELECT * FROM ai_tool_configuration WHERE id = #{id}")
  AiToolConfiguration findById(@Param("id") Long id);

  @Select(
      "SELECT * FROM ai_tool_configuration WHERE enabled = 1 "
          + "ORDER BY default_tool DESC, updated_at DESC, id DESC LIMIT 1")
  AiToolConfiguration findActive();

  @Select("SELECT COUNT(*) FROM ai_tool_configuration")
  int count();

  @Insert(
      "INSERT INTO ai_tool_configuration(name, provider, base_url, model, api_key_ciphertext, enabled, "
          + "default_tool, created_at, updated_at) VALUES(#{name}, #{provider}, #{baseUrl}, #{model}, "
          + "#{apiKeyCiphertext}, #{enabled}, #{defaultTool}, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(AiToolConfiguration value);

  @Update(
      "UPDATE ai_tool_configuration SET name=#{name}, provider=#{provider}, base_url=#{baseUrl}, "
          + "model=#{model}, api_key_ciphertext=#{apiKeyCiphertext}, enabled=#{enabled}, "
          + "default_tool=#{defaultTool}, updated_at=#{updatedAt} WHERE id=#{id}")
  int update(AiToolConfiguration value);

  @Update(
      "UPDATE ai_tool_configuration SET default_tool = 0 WHERE default_tool = 1 AND id <> #{id}")
  int clearOtherDefaults(@Param("id") Long id);

  @Delete("DELETE FROM ai_tool_configuration WHERE id = #{id}")
  int delete(@Param("id") Long id);
}
