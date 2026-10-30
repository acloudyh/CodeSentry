package io.codesentry.ai.mapper;

import io.codesentry.ai.model.AiRuntimeConfiguration;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

public interface AiRuntimeConfigurationMapper {

    @Select("SELECT enabled, base_url, model, updated_at FROM ai_runtime_configuration WHERE id = 1")
    AiRuntimeConfiguration find();

    @Insert("INSERT INTO ai_runtime_configuration(id, enabled, base_url, model, updated_at) " +
            "VALUES(1, #{enabled}, #{baseUrl}, #{model}, #{updatedAt}) " +
            "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), base_url = VALUES(base_url), " +
            "model = VALUES(model), updated_at = VALUES(updated_at)")
    int upsert(AiRuntimeConfiguration configuration);
}
