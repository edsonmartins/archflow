/**
 * RCHFLW-80: "Disparar agora" só enfileira a invocação. O gatilho dizia "disparado" e o desfecho do
 * agente não chegava a lugar nenhum; agora o disparo devolve o requestId e a tela consulta
 * GET /archflow/agents/invocations/{requestId} até o resultado ficar final.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

const apiMock = vi.hoisted(() => ({ invoke: vi.fn(), message: vi.fn(), invocation: vi.fn() }))
const show = vi.hoisted(() => vi.fn())

vi.mock('@mantine/notifications', () => ({ notifications: { show } }))
vi.mock('../../services/agent-playground-api', async importOriginal => {
  const real = await importOriginal<typeof import('../../services/agent-playground-api')>()
  return { ...real, agentPlaygroundApi: apiMock }
})

import { followTriggerRun } from '../ScheduledTriggersPage'
import { InvocationNotFoundError } from '../../services/agent-playground-api'

const t = ((k: string, o?: Record<string, unknown>) => (o ? `${k} ${JSON.stringify(o)}` : k)) as any
const trigger = { name: 'noturno', tenantId: 'tenant-a' }
const result = (status: string, extra: Record<string, unknown> = {}) => ({
  requestId: 'req-1', tenantId: 'tenant-a', agentId: 'text-transform-tool', status, attempts: 1, ...extra,
})

beforeEach(() => vi.clearAllMocks())

describe('followTriggerRun', () => {
  it('consulta até COMPLETED, no tenant do gatilho, e avisa que o agente concluiu', async () => {
    apiMock.invocation
      .mockResolvedValueOnce(result('QUEUED'))
      .mockResolvedValueOnce(result('RUNNING'))
      .mockResolvedValueOnce(result('COMPLETED', { output: 'RELATÓRIO' }))

    const r = await followTriggerRun(trigger, 'req-1', t, { intervalMs: 0 })

    expect(r?.status).toBe('COMPLETED')
    expect(apiMock.invocation).toHaveBeenCalledTimes(3)
    expect(apiMock.invocation).toHaveBeenCalledWith('req-1', 'tenant-a')
    expect(show).toHaveBeenCalledTimes(1)
    expect(show.mock.calls[0][0]).toMatchObject({ color: 'green', title: 'triggers.run.completed' })
  })

  it('FAILED mostra o erro do agente', async () => {
    apiMock.invocation.mockResolvedValueOnce(result('FAILED', { error: 'operação voar não existe' }))

    await followTriggerRun(trigger, 'req-1', t, { intervalMs: 0 })

    expect(show.mock.calls[0][0]).toMatchObject({ color: 'red', message: 'operação voar não existe' })
  })

  it('tolera alguns 404 logo depois do disparo', async () => {
    apiMock.invocation
      .mockRejectedValueOnce(new InvocationNotFoundError('nf'))
      .mockResolvedValueOnce(result('COMPLETED'))

    const r = await followTriggerRun(trigger, 'req-1', t, { intervalMs: 0 })

    expect(r?.status).toBe('COMPLETED')
  })

  it('404 persistente ou prazo esgotado: avisa com o requestId em vez de ficar calado', async () => {
    apiMock.invocation.mockRejectedValue(new InvocationNotFoundError('nf'))
    expect(await followTriggerRun(trigger, 'req-1', t, { intervalMs: 0 })).toBeNull()
    expect(show.mock.calls[0][0]).toMatchObject({ color: 'yellow' })
    expect(show.mock.calls[0][0].message).toContain('triggers.run.queryFailed')
    expect(show.mock.calls[0][0].message).toContain('req-1')

    show.mockClear()
    apiMock.invocation.mockReset()
    apiMock.invocation.mockResolvedValue(result('RUNNING'))
    expect(await followTriggerRun(trigger, 'req-1', t, { intervalMs: 0, deadlineMs: 0 })).toBeNull()
    expect(show.mock.calls[0][0].message).toContain('triggers.run.stillRunning')
  })
})
