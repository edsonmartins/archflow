/**
 * RCHFLW-73: fluxos e execuções são do tenant da requisição. Ao entrar num tenant (personificação) ou
 * sair dele, a lista em memória é do tenant anterior — ela é descartada e recarregada, e uma resposta
 * que chegue atrasada do tenant anterior não volta a povoá-la.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

const workflowApiMock = vi.hoisted(() => ({
  list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn(), execute: vi.fn(),
}))
const executionApiMock = vi.hoisted(() => ({ list: vi.fn() }))
vi.mock('../../services/api', () => ({ workflowApi: workflowApiMock, executionApi: executionApiMock }))

import { useWorkflowStore } from '../workflow-store'
import { useTenantStore } from '../useTenantStore'

const resumo = (id: string) => ({
  id, name: id, description: '', version: '1.0.0', status: 'draft', updatedAt: '', stepCount: 0,
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}

beforeEach(() => {
  // Volta ao tenant padrão antes de zerar os mocks: a própria volta dispara uma recarga.
  useTenantStore.setState({ impersonating: null })
  vi.clearAllMocks()
  useWorkflowStore.setState({ workflows: [], currentWorkflow: null, executions: [], loading: false, error: null })
})

describe('workflow-store ao trocar de tenant', () => {
  it('entrar num tenant descarta a lista, o fluxo aberto e as execuções e recarrega a lista', async () => {
    useWorkflowStore.setState({
      workflows: [resumo('wf-do-padrao')] as never,
      currentWorkflow: { id: 'wf-do-padrao' } as never,
      executions: [{ id: 'exec-do-padrao' }] as never,
    })
    workflowApiMock.list.mockResolvedValue([resumo('wf-do-b')])

    useTenantStore.getState().startImpersonation({ id: 'tenant-b', name: 'B' } as never)

    expect(useWorkflowStore.getState().currentWorkflow).toBeNull()
    expect(useWorkflowStore.getState().executions).toEqual([])
    expect(workflowApiMock.list).toHaveBeenCalledTimes(1)
    await vi.waitFor(() =>
      expect(useWorkflowStore.getState().workflows.map((w) => w.id)).toEqual(['wf-do-b']))
  })

  it('a lista pedida antes da troca, que chega depois, não repovoa o tenant novo', async () => {
    const antiga = deferred<unknown[]>()
    workflowApiMock.list.mockReturnValueOnce(antiga.promise).mockResolvedValueOnce([resumo('wf-do-b')])

    const pedidoAntigo = useWorkflowStore.getState().fetchWorkflows()
    useTenantStore.getState().startImpersonation({ id: 'tenant-b', name: 'B' } as never)
    await vi.waitFor(() =>
      expect(useWorkflowStore.getState().workflows.map((w) => w.id)).toEqual(['wf-do-b']))

    antiga.resolve([resumo('wf-do-padrao')])
    await pedidoAntigo

    expect(useWorkflowStore.getState().workflows.map((w) => w.id)).toEqual(['wf-do-b'])
  })

  it('sair da personificação também recarrega; continuar no mesmo tenant não', async () => {
    workflowApiMock.list.mockResolvedValue([])
    useTenantStore.getState().startImpersonation({ id: 'tenant-b', name: 'B' } as never)
    useTenantStore.getState().startImpersonation({ id: 'tenant-b', name: 'B (renomeado)' } as never)
    expect(workflowApiMock.list).toHaveBeenCalledTimes(1)

    useTenantStore.getState().exitImpersonation()
    expect(workflowApiMock.list).toHaveBeenCalledTimes(2)
  })
})
