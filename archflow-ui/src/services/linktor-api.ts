import { api } from './api'

export interface LinktorConfig {
    enabled: boolean
    apiBaseUrl: string
    apiKey: string
    accessToken: string
    mcpCommand: string
    timeoutSeconds: number
}

/**
 * Reads/updates the Linktor (omnichannel messaging) integration. The
 * GET endpoint returns both the API key and access token masked; leave
 * the masked value intact in the PUT payload to preserve the stored
 * secret.
 *
 * <p>Once enabled, Linktor shows up in the MCP admin page and as a
 * selectable server in the workflow editor PropertyPanel.</p>
 */
export const linktorApi = {
    get:    () => api.get<LinktorConfig>('/admin/linktor'),
    update: (cfg: LinktorConfig) => api.put<LinktorConfig>('/admin/linktor', cfg),
}
