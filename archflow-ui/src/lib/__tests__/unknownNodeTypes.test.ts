/**
 * RCHFLW-134: o que conta como tipo de nó desconhecido para o aviso do Salvar YAML. O designer não conhece os
 * plugins carregados, então "desconhecido" é: fora da paleta, fora dos tipos que o motor resolve sem catálogo e
 * fora do catálogo do servidor. Sem o catálogo não há como afirmar — nenhum aviso.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

const catalogMock = vi.hoisted(() => ({ listAgents: vi.fn(), listAssistants: vi.fn(), listTools: vi.fn() }))
vi.mock('../../services/catalog-api', () => ({ catalogApi: catalogMock }))

import { unknownNodeTypeSteps } from '../unknownNodeTypes'

beforeEach(() => {
  vi.clearAllMocks()
  catalogMock.listAgents.mockResolvedValue([{ id: 'Agente-Do-Plugin' }])
  catalogMock.listAssistants.mockResolvedValue([])
  catalogMock.listTools.mockResolvedValue([{ id: 'calculator' }])
})

describe('unknownNodeTypeSteps', () => {
  it('aponta só o tipo que ninguém conhece, pelo componentId e, sem ele, pelo type', async () => {
    const unknown = await unknownNodeTypeSteps([
      { id: 'a', label: 'Chat', componentId: 'llm-chat', type: 'llm-chat' },
      { id: 'b', label: 'Estranho', type: 'foo-bar' },
      { id: 'c', label: 'Calc', componentId: 'calculator', type: 'tool' },
      { id: 'd', type: 'agente-do-plugin' },
      { id: 'e', label: 'MCP', componentId: 'mcp-agent' },
      { id: 'f', label: 'Alias', type: 'chat' },
      { id: 'g', label: '', componentId: 'Outro-Tipo' },
    ])

    expect(unknown).toEqual([
      { id: 'b', label: 'Estranho', type: 'foo-bar' },
      { id: 'g', label: 'g', type: 'outro-tipo' },
    ])
  })

  it('anotações do canvas não são passos', async () => {
    expect(await unknownNodeTypeSteps([{ id: 'n', componentId: 'sticky-note' }])).toEqual([])
  })

  it('só tipos conhecidos: nem consulta o catálogo', async () => {
    expect(await unknownNodeTypeSteps([{ id: 'a', componentId: 'input' }, { id: 'b', type: 'decision' }])).toEqual([])
    expect(catalogMock.listTools).not.toHaveBeenCalled()
  })

  it('catálogo indisponível: null, sem afirmar que o tipo não existe', async () => {
    catalogMock.listTools.mockRejectedValue(new Error('503'))
    expect(await unknownNodeTypeSteps([{ id: 'b', type: 'foo-bar' }])).toBeNull()
  })
})
