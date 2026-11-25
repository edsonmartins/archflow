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
        /**
         * Command line that starts the Linktor MCP server (e.g. a {@code node} invocation). Its executable
         * must be on the {@code McpCommandPolicy} allow-list, which by default does not include
         * {@code linktor-mcp}.
         */
        String mcpCommand,
        long timeoutSeconds) {

    public LinktorConfigDto {
        if (timeoutSeconds <= 0) timeoutSeconds = 30;
    }

    /**
     * Máscara de um segredo de até 8 caracteres. Curto demais para mostrar início e fim sem revelar
     * quase tudo, então não mostra nada.
     */
    public static final String SHORT_SECRET_MASK = "***";

    /** Separador da máscara de segredos longos ({@code abcd…wxyz}). */
    public static final String MASK_ELLIPSIS = "…";

    private static String mask(String v) {
        if (v == null || v.isBlank()) return v;
        if (v.length() <= 8) return SHORT_SECRET_MASK;
        return v.substring(0, 4) + MASK_ELLIPSIS + v.substring(v.length() - 4);
    }

    /**
     * {@code true} quando o valor é uma máscara que o próprio servidor devolveu no GET — de qualquer um
     * dos dois formatos. A UI reenvia o placeholder intacto para dizer "mantenha a chave atual"; se só o
     * formato longo fosse reconhecido, uma chave curta seria trocada pelo literal {@code ***} no
     * segundo save (RCHFLW-70). Fica aqui, ao lado de {@link #mask}, para os dois não divergirem.
     */
    public static boolean isMasked(String v) {
        return v != null && (v.equals(SHORT_SECRET_MASK) || v.contains(MASK_ELLIPSIS));
    }

    public LinktorConfigDto withMaskedSecrets() {
        return new LinktorConfigDto(enabled, apiBaseUrl, mask(apiKey), mask(accessToken),
                mcpCommand, timeoutSeconds);
    }
}
