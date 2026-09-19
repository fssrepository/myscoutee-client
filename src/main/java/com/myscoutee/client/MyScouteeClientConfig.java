package com.myscoutee.client;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

public record MyScouteeClientConfig(
        URI baseUrl,
        String token,
        UUID clientId) {

    public MyScouteeClientConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(clientId, "clientId");
        String normalizedToken = token == null ? "" : token.trim();
        if (normalizedToken.isEmpty()) {
            throw new IllegalArgumentException("token is required");
        }
        token = normalizedToken;

        String normalizedBaseUrl = baseUrl.toString().replaceAll("/+$", "");
        if (!(normalizedBaseUrl.startsWith("https://") || normalizedBaseUrl.startsWith("http://localhost"))) {
            throw new IllegalArgumentException("baseUrl must use HTTPS (HTTP is allowed only for localhost)");
        }
        baseUrl = URI.create(normalizedBaseUrl);
    }

    public static MyScouteeClientConfig of(String baseUrl, String token, String clientId) {
        return new MyScouteeClientConfig(
                URI.create(baseUrl == null ? "" : baseUrl.trim()),
                token,
                UUID.fromString(clientId == null ? "" : clientId.trim()));
    }
}
