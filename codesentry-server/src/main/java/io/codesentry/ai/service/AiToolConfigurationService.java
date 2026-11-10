package io.codesentry.ai.service;

import io.codesentry.ai.dto.AiToolConfigurationRequest;
import io.codesentry.ai.dto.AiToolConfigurationView;
import io.codesentry.ai.mapper.AiRuntimeConfigurationMapper;
import io.codesentry.ai.mapper.AiToolConfigurationMapper;
import io.codesentry.ai.model.AiRuntimeConfiguration;
import io.codesentry.ai.model.AiToolConfiguration;
import io.codesentry.ai.secret.AiApiKeyStore;
import io.codesentry.ai.secret.AiToolSecretCipher;
import io.codesentry.common.error.BusinessException;
import io.codesentry.config.CodeSentryProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@DependsOnDatabaseInitialization
public class AiToolConfigurationService {
    private final AiToolConfigurationMapper mapper;
    private final AiRuntimeConfigurationMapper legacyMapper;
    private final AiToolSecretCipher cipher;
    private final AiApiKeyStore legacyKeyStore;
    private final CodeSentryProperties properties;

    public AiToolConfigurationService(AiToolConfigurationMapper mapper, AiRuntimeConfigurationMapper legacyMapper,
                                      AiToolSecretCipher cipher, AiApiKeyStore legacyKeyStore,
                                      CodeSentryProperties properties) {
        this.mapper = mapper; this.legacyMapper = legacyMapper; this.cipher = cipher;
        this.legacyKeyStore = legacyKeyStore; this.properties = properties;
    }

    @PostConstruct
    public synchronized void initialize() {
        if (mapper.count() == 0) migrateLegacyConfiguration();
        applyActiveConfiguration();
    }

    public List<AiToolConfigurationView> list() {
        return mapper.findAll().stream().map(AiToolConfigurationView::new).collect(Collectors.toList());
    }

    public AiToolConfigurationView requireView(Long id) { return new AiToolConfigurationView(require(id)); }

    public synchronized AiToolConfigurationView create(AiToolConfigurationRequest request) {
        String apiKey = trim(request.getApiKey());
        if (!StringUtils.hasText(apiKey)) throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入 API Key");
        AiToolConfiguration value = fromRequest(request, null);
        value.setApiKeyCiphertext(cipher.encrypt(apiKey));
        value.setCreatedAt(LocalDateTime.now()); value.setUpdatedAt(value.getCreatedAt());
        if (mapper.count() == 0) { value.setEnabled(true); value.setDefaultTool(true); }
        mapper.insert(value);
        if (Boolean.TRUE.equals(value.getDefaultTool())) mapper.clearOtherDefaults(value.getId());
        applyActiveConfiguration();
        return requireView(value.getId());
    }

    public synchronized AiToolConfigurationView update(Long id, AiToolConfigurationRequest request) {
        AiToolConfiguration existing = require(id);
        AiToolConfiguration value = fromRequest(request, existing);
        String apiKey = trim(request.getApiKey());
        value.setApiKeyCiphertext(StringUtils.hasText(apiKey) ? cipher.encrypt(apiKey) : existing.getApiKeyCiphertext());
        value.setCreatedAt(existing.getCreatedAt()); value.setUpdatedAt(LocalDateTime.now());
        mapper.update(value);
        if (Boolean.TRUE.equals(value.getDefaultTool())) mapper.clearOtherDefaults(id);
        applyActiveConfiguration();
        return requireView(id);
    }

    public synchronized void delete(Long id) {
        AiToolConfiguration existing = require(id);
        mapper.delete(id);
        if (Boolean.TRUE.equals(existing.getDefaultTool())) {
            AiToolConfiguration next = mapper.findActive();
            if (next != null) { next.setDefaultTool(true); next.setUpdatedAt(LocalDateTime.now()); mapper.update(next); }
        }
        applyActiveConfiguration();
    }

    public AiToolConfiguration active() { return mapper.findActive(); }

    private AiToolConfiguration require(Long id) {
        AiToolConfiguration value = mapper.findById(id);
        if (value == null) throw new BusinessException(HttpStatus.NOT_FOUND, "工具配置不存在");
        return value;
    }

    private AiToolConfiguration fromRequest(AiToolConfigurationRequest request, AiToolConfiguration existing) {
        String baseUrl = trim(request.getBaseUrl());
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "请输入有效的服务地址");
        }
        AiToolConfiguration value = new AiToolConfiguration();
        if (existing != null) value.setId(existing.getId());
        value.setName(trim(request.getName())); value.setProvider(trim(request.getProvider()).toUpperCase(Locale.ROOT));
        value.setBaseUrl(baseUrl.replaceAll("/+$", "")); value.setModel(trim(request.getModel()));
        value.setEnabled(request.isEnabled());
        value.setDefaultTool(request.isDefaultTool()
                || (existing != null && Boolean.TRUE.equals(existing.getDefaultTool())));
        if (value.getDefaultTool()) value.setEnabled(true);
        return value;
    }

    private void migrateLegacyConfiguration() {
        AiRuntimeConfiguration legacy = legacyMapper.find();
        String baseUrl = legacy == null ? properties.getAi().getBaseUrl() : legacy.getBaseUrl();
        String model = legacy == null ? properties.getAi().getModel() : legacy.getModel();
        boolean enabled = legacy == null ? properties.getAi().isEnabled() : Boolean.TRUE.equals(legacy.getEnabled());
        String apiKey = properties.getAi().getApiKey();
        if (!StringUtils.hasText(apiKey)) apiKey = legacyKeyStore.load().orElse(null);
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(model) || !StringUtils.hasText(apiKey)) return;
        AiToolConfiguration value = new AiToolConfiguration();
        value.setName("DeepSeek 默认工具"); value.setProvider("DEEPSEEK"); value.setBaseUrl(baseUrl);
        value.setModel(model); value.setApiKeyCiphertext(cipher.encrypt(apiKey)); value.setEnabled(enabled);
        value.setDefaultTool(true); value.setCreatedAt(LocalDateTime.now()); value.setUpdatedAt(value.getCreatedAt());
        mapper.insert(value);
    }

    private void applyActiveConfiguration() {
        AiToolConfiguration active = mapper.findActive();
        synchronized (properties.getAi()) {
        properties.getAi().setEnabled(active != null);
        properties.getAi().setProvider(active == null ? "OPENAI_COMPATIBLE" : active.getProvider());
        properties.getAi().setBaseUrl(active == null ? null : active.getBaseUrl());
        properties.getAi().setModel(active == null ? null : active.getModel());
        properties.getAi().setApiKey(active == null ? null : cipher.decrypt(active.getApiKeyCiphertext()));
        }
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }
}
