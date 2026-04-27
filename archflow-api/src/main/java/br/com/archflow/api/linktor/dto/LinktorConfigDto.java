package br.com.archflow.api.linktor.dto;

/**
 * Runtime-editable configuration for the Linktor integration.
 *
 * <p>Linktor is an open-source omnichannel messaging platform with an
 * MCP server that exposes ~51 tools (conversations, messages, contacts,
 * channels, bots, analytics and a Visual Response Engine). We drive it
 * via stdio using the {@code linktor-mcp} binary shipped by the Node
 * package {@code @linktor/mcp-server}. The binary honours
 * {@code LINKTOR_API_URL}, {@code LINKTOR_API_KEY} and
 * {@code LINKTOR_ACCESS_TOKEN} environment variables — we forward those
 * from this DTO.
 *
 * <p>The {@code apiKey} is stored server-side and masked on read.
 */
public record LinktorConfigDto(
        boolean enabled,
        String apiBaseUrl,
        String apiKey,
        String accessToken,
        /** Absolute path to the {@code linktor-mcp} binary or a {@code node} invocation. */
        String mcpCommand,
        long timeoutSeconds) {

    public LinktorConfigDto {
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }

    private static String mask(String v) {
        if (v == null || v.isBlank()) return v;
        if (v.length() <= 8) return "***";
        return v.substring(0, 4) + "…" + v.substring(v.length() - 4);
    }

    public LinktorConfigDto withMaskedSecrets() {
        return new LinktorConfigDto(enabled, apiBaseUrl, mask(apiKey), mask(accessToken),
                mcpCommand, timeoutSeconds);
    }
}
