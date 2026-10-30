package io.codesentry.ai.secret;

import java.util.Optional;

public interface AiApiKeyStore {
    Optional<String> load();
    void save(String apiKey);
}
