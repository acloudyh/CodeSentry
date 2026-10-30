package io.codesentry.ai.service;

import io.codesentry.ai.dto.AiReviewConfigurationRequest;
import io.codesentry.ai.dto.AiReviewConfigurationView;
import io.codesentry.ai.dto.AiToolConfigurationRequest;
import io.codesentry.ai.dto.AiToolConfigurationView;
import io.codesentry.ai.model.AiToolConfiguration;
import io.codesentry.ai.provider.AiReviewProvider;
import io.codesentry.common.error.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AiReviewConfigurationService {
    private final AiToolConfigurationService tools;
    private final AiReviewProvider provider;

    public AiReviewConfigurationService(AiToolConfigurationService tools, AiReviewProvider provider) {
        this.tools = tools; this.provider = provider;
    }

    public AiReviewConfigurationView configuration() {
        return view();
    }

    public synchronized AiReviewConfigurationView configure(AiReviewConfigurationRequest request) {
        AiToolConfiguration active = tools.active();
        AiToolConfigurationRequest value = new AiToolConfigurationRequest();
        value.setName(active == null ? "默认工具" : active.getName());
        value.setProvider(active == null ? "OPENAI_COMPATIBLE" : active.getProvider());
        value.setBaseUrl(trim(request.getBaseUrl())); value.setModel(trim(request.getModel()));
        value.setApiKey(trim(request.getApiKey())); value.setEnabled(request.isEnabled()); value.setDefaultTool(true);
        if (active == null) tools.create(value); else tools.update(active.getId(), value);
        return view();
    }

    private AiReviewConfigurationView view() {
        AiToolConfiguration active = tools.active();
        return new AiReviewConfigurationView(active != null, provider.providerName(),
                active == null ? null : active.getModel(), active == null ? null : active.getBaseUrl(),
                active != null && StringUtils.hasText(active.getApiKeyCiphertext()));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
