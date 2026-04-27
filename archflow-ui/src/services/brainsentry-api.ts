import { api } from './api'

export interface BrainSentryConfig {
    enabled: boolean
    baseUrl: string
    apiKey: string
    tenantId: string
    maxTokenBudget: number
    deepAnalysisEnabled: boolean
    timeoutSeconds: number
}

/**
 * Reads/updates BrainSentry configuration. The GET endpoint returns
 * the API key masked (e.g. {@code abcd…wxyz}); leaving the masked value
 * intact in the PUT payload keeps the stored key unchanged.
 */
export const brainsentryApi = {
    get:    () => api.get<BrainSentryConfig>('/admin/brainsentry'),
    update: (config: BrainSentryConfig) => api.put<BrainSentryConfig>('/admin/brainsentry', config),
}
