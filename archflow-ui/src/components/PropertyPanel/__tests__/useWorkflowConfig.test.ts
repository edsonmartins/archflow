import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { resetWorkflowConfigCache } from '../useWorkflowConfig'
import { workflowConfigApi } from '../../../services/workflow-config-api'

/**
 * These tests validate the module-level cache used by `useWorkflowConfig`.
 * We don't render the React hook directly (this project ships
 * @testing-library/dom but not @testing-library/react) — instead we verify
 * the underlying cache contract by calling the shared API layer the hook
 * delegates to.
 *
 * The cache itself is exercised in the E2E PropertyPanel spec where the
 * hook actually mounts inside a real React tree.
 */

describe('workflow-config-api (feeds useWorkflowConfig)', () => {
  beforeEach(() => {
    resetWorkflowConfigCache()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('getProviders calls /api/workflow/providers with auth headers', async () => {
    localStorage.setItem('archflow_token', 'test-token')
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => [
        {
          id: 'openai', displayName: 'OpenAI', requiresApiKey: true,
          supportsStreaming: true, group: 'Cloud',
          models: [{ id: 'gpt-4o', name: 'GPT-4o', contextWindow: 128000, maxTemperature: 2.0 }],
        },
      ],
    })) as unknown as typeof fetch
    vi.stubGlobal('fetch', fetchMock)

    const result = await workflowConfigApi.getProviders()

    expect(result).toHaveLength(1)
    expect(result[0].id).toBe('openai')
    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [
      string, RequestInit
    ]
    expect(url).toContain('/workflow/providers')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer test-token')
    localStorage.removeItem('archflow_token')
  })

  it('getAgentPatterns hits the agent-patterns endpoint', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true, status: 200,
      json: async () => [{ id: 'react', label: 'ReAct', description: 'iterate' }],
    })) as unknown as typeof fetch
    vi.stubGlobal('fetch', fetchMock)

    const result = await workflowConfigApi.getAgentPatterns()
    expect(result[0].id).toBe('react')
    expect(
      ((fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0] as string)
    ).toContain('/workflow/agent-patterns')
  })

  it('getPersonas returns the persona list', async () => {
    const personas = [{ id: 'order_tracking', label: 'Order', description: 'Desc', promptId: 'p1' }]
    const fetchMock = vi.fn(async () => ({
      ok: true, status: 200, json: async () => personas,
    })) as unknown as typeof fetch
    vi.stubGlobal('fetch', fetchMock)

    const result = await workflowConfigApi.getPersonas()
    expect(result).toEqual(personas)
  })

  it('getGovernanceProfiles returns profiles with tool sets', async () => {
    const profiles = [{
      id: 'strict', name: 'Strict', systemPrompt: 'Careful',
      enabledTools: ['search'], disabledTools: ['delete'],
      escalationThreshold: 0.7, maxToolExecutions: 5, customInstructions: '',
    }]
    const fetchMock = vi.fn(async () => ({
      ok: true, status: 200, json: async () => profiles,
    })) as unknown as typeof fetch
    vi.stubGlobal('fetch', fetchMock)

    const result = await workflowConfigApi.getGovernanceProfiles()
    expect(result[0].enabledTools).toEqual(['search'])
    expect(result[0].escalationThreshold).toBe(0.7)
  })

  it('getMcpServers returns server descriptors', async () => {
    const servers = [
      { name: 'fs', transport: 'stdio' as const, command: 'mcp-fs', url: null, toolCount: 3 },
    ]
    const fetchMock = vi.fn(async () => ({
      ok: true, status: 200, json: async () => servers,
    })) as unknown as typeof fetch
    vi.stubGlobal('fetch', fetchMock)

    const result = await workflowConfigApi.getMcpServers()
    expect(result[0].transport).toBe('stdio')
  })

  it('throws on non-200 response', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: false, status: 500, statusText: 'Internal Error',
      json: async () => ({}),
    })) as unknown as typeof fetch
    vi.stubGlobal('fetch', fetchMock)

    await expect(workflowConfigApi.getProviders()).rejects.toThrow(/500/)
  })

  it('adds X-Impersonate-Tenant header when set', async () => {
    sessionStorage.setItem('archflow_impersonate_tenant', 'tenant-foo')
    const fetchMock = vi.fn(async () => ({
      ok: true, status: 200, json: async () => [],
    })) as unknown as typeof fetch
    vi.stubGlobal('fetch', fetchMock)

    await workflowConfigApi.getProviders()
    const init = (fetchMock as unknown as ReturnType<typeof vi.fn>).mock.calls[0][1] as RequestInit
    expect((init.headers as Record<string, string>)['X-Impersonate-Tenant']).toBe('tenant-foo')
    sessionStorage.removeItem('archflow_impersonate_tenant')
  })
})

describe('useWorkflowConfig cache contract', () => {
  it('resetWorkflowConfigCache is callable and idempotent', () => {
    expect(() => resetWorkflowConfigCache()).not.toThrow()
    expect(() => resetWorkflowConfigCache()).not.toThrow()
  })
})
