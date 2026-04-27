package br.com.archflow.api.linktor;

import br.com.archflow.api.linktor.dto.LinktorConfigDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP client that talks directly to Linktor's REST API
 * ({@code /api/v1/**}). Used by the admin inbox page and by the
 * escalation / publisher hooks.
 *
 * <p>We avoid adding a heavyweight HTTP library — the JDK
 * {@link HttpClient} is enough for the handful of JSON calls we make.
 * Auth is via {@code Authorization: Bearer &lt;token&gt;} when
 * {@code accessToken} is set, falling back to {@code X-API-Key:
 * &lt;apiKey&gt;} otherwise (matches Linktor's middleware).
 *
 * <p>This client is intentionally stateless besides configuration. A
 * fresh instance is cheap; callers can recreate it when the admin
 * updates the config via {@link LinktorConfigController#update}.
 */
public class LinktorHttpClient {

    private static final Logger log = LoggerFactory.getLogger(LinktorHttpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final LinktorConfigController configController;
    private final HttpClient http;

    public LinktorHttpClient(LinktorConfigController configController) {
        this.configController = configController;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private LinktorConfigDto requireEnabled() {
        LinktorConfigDto cfg = rawConfig();
        if (!cfg.enabled()) {
            throw new IllegalStateException(
                    "Linktor integration is disabled; enable it in /admin/linktor first");
        }
        if (cfg.apiBaseUrl() == null || cfg.apiBaseUrl().isBlank()) {
            throw new IllegalStateException("Linktor apiBaseUrl is not configured");
        }
        return cfg;
    }

    /**
     * Reads the unmasked config directly from the controller. We can
     * bypass the masking because we're running server-side — the
     * browser never sees the plaintext key.
     */
    private LinktorConfigDto rawConfig() {
        if (configController instanceof br.com.archflow.api.linktor.impl.LinktorConfigControllerImpl impl) {
            return impl.rawSnapshot();
        }
        // Fallback: use masked (broken for real calls, but surfaces the
        // misconfiguration clearly).
        return configController.get();
    }

    // ── GET helpers ──────────────────────────────────────────────────

    public List<Map<String, Object>> listConversations(int limit, int offset) throws IOException {
        return fetchArray(String.format("/conversations?limit=%d&offset=%d", limit, offset));
    }

    public Map<String, Object> getConversation(String id) throws IOException {
        return fetchObject("/conversations/" + enc(id));
    }

    public List<Map<String, Object>> listMessages(String conversationId, int limit) throws IOException {
        return fetchArray("/conversations/" + enc(conversationId) + "/messages?limit=" + limit);
    }

    public List<Map<String, Object>> listContacts(int limit, int offset) throws IOException {
        return fetchArray(String.format("/contacts?limit=%d&offset=%d", limit, offset));
    }

    public List<Map<String, Object>> listChannels() throws IOException {
        return fetchArray("/channels");
    }

    // ── POST helpers ─────────────────────────────────────────────────

    public Map<String, Object> sendMessage(String conversationId, String text) throws IOException {
        return postObject("/conversations/" + enc(conversationId) + "/messages",
                Map.of("content", text, "type", "text"));
    }

    public Map<String, Object> assignConversation(String conversationId, String userId) throws IOException {
        return postObject("/conversations/" + enc(conversationId) + "/assign",
                Map.of("userId", userId));
    }

    public Map<String, Object> resolveConversation(String conversationId) throws IOException {
        return postObject("/conversations/" + enc(conversationId) + "/resolve", Map.of());
    }

    // ── Internals ────────────────────────────────────────────────────

    private List<Map<String, Object>> fetchArray(String path) throws IOException {
        HttpResponse<String> res = call("GET", path, null);
        if (res.statusCode() / 100 != 2) throw httpError(path, res);
        JsonNode root = MAPPER.readTree(res.body());
        JsonNode array = root.isArray() ? root : root.path("data");
        return MAPPER.convertValue(array, new TypeReference<>() {});
    }

    private Map<String, Object> fetchObject(String path) throws IOException {
        HttpResponse<String> res = call("GET", path, null);
        if (res.statusCode() / 100 != 2) throw httpError(path, res);
        return MAPPER.readValue(res.body(), new TypeReference<>() {});
    }

    private Map<String, Object> postObject(String path, Object body) throws IOException {
        HttpResponse<String> res = call("POST", path, body);
        if (res.statusCode() / 100 != 2) throw httpError(path, res);
        if (res.body() == null || res.body().isBlank()) return Map.of();
        return MAPPER.readValue(res.body(), new TypeReference<>() {});
    }

    private HttpResponse<String> call(String method, String path, Object body) throws IOException {
        LinktorConfigDto cfg = requireEnabled();
        URI uri = URI.create(stripTrailingSlash(cfg.apiBaseUrl()) + path);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .header("Accept", "application/json");

        if (cfg.accessToken() != null && !cfg.accessToken().isBlank()) {
            req.header("Authorization", "Bearer " + cfg.accessToken());
        } else if (cfg.apiKey() != null && !cfg.apiKey().isBlank()) {
            req.header("X-API-Key", cfg.apiKey());
        }

        HttpRequest.BodyPublisher publisher = BodyPublishers.noBody();
        if (body != null) {
            String json = MAPPER.writeValueAsString(body);
            publisher = BodyPublishers.ofString(json);
            req.header("Content-Type", "application/json");
        }

        switch (method) {
            case "GET"    -> req.GET();
            case "POST"   -> req.POST(publisher);
            case "PUT"    -> req.PUT(publisher);
            case "DELETE" -> req.DELETE();
            default       -> throw new IllegalArgumentException("unsupported method: " + method);
        }

        try {
            return http.send(req.build(), BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Linktor request interrupted", e);
        }
    }

    private IOException httpError(String path, HttpResponse<String> res) {
        String preview = res.body();
        if (preview != null && preview.length() > 200) preview = preview.substring(0, 200) + "…";
        String msg = "Linktor " + path + " returned HTTP " + res.statusCode()
                + (preview != null && !preview.isBlank() ? (" — " + preview) : "");
        log.warn(msg);
        return new IOException(msg);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String enc(String raw) {
        return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }
}
