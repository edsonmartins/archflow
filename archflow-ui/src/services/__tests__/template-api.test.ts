/**
 * Galeria de templates: o catálogo do backend é MESCLADO com o local (RCHFLW-46) e os templates do
 * backend chegam com o grafo real, marcados quando têm nós sem executor (RCHFLW-18).
 *
 * Antes: `resolveList` devolvia a lista do backend sozinha quando ela vinha não vazia — e ela sempre
 * vem (o registro SPI carrega 4 templates) —, então os 6 templates locais com passos sumiam; e os 4
 * do backend chegavam com `steps: []`, de modo que o clone criava um fluxo vazio.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

const apiMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const workflowApiMock = vi.hoisted(() => ({ create: vi.fn() }))
vi.mock('../api', () => ({ api: apiMock, workflowApi: workflowApiMock }))

import {
  mergeCatalogs, templateApi, toWorkflowTemplate, type BackendTemplate,
} from '../template-api'
import { listTemplates as bundledList } from '../../data/templates'
import { connectionsFromSteps } from '../../components/FlowCanvas/workflowSerde'

/** Um template SPI como o backend o serializa: documento canônico, arestas dentro dos passos. */
const customerSupport: BackendTemplate = {
  id: 'customer-support',
  displayName: 'Customer Support with RAG',
  description: 'Multi-agent customer support',
  category: 'support',
  tags: ['rag'],
  stepCount: 3,
  steps: [
    {
      id: 'input', type: 'input', componentId: 'input', label: 'Customer Inquiry Input',
      position: { x: 80, y: 80 }, configuration: { schema: { message: 'string' } },
      connections: [{ sourceId: 'input', targetId: 'intent', isErrorPath: false }],
    },
    {
      id: 'intent', type: 'llm', componentId: 'llm', label: 'Intent Classifier',
      position: { x: 340, y: 80 }, configuration: { temperature: 0.3 },
      connections: [{ sourceId: 'intent', targetId: 'kb', isErrorPath: false, condition: "${intent} != 'general'" }],
    },
    {
      id: 'kb', type: 'vector-search', componentId: 'vector-search', label: 'Knowledge Base Search',
      position: { x: 600, y: 80 }, configuration: { topK: 5 }, connections: [],
    },
  ],
  unsupportedNodeTypes: ['input', 'llm', 'vector-search'],
}

beforeEach(() => {
  vi.clearAllMocks()
  templateApi._resetCache()
  // public/templates indisponível: o catálogo local vem do pacote (as mesmas 6 receitas).
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
})

describe('toWorkflowTemplate', () => {
  it('traz os passos do backend no formato do designer, com as arestas e a config', () => {
    const tpl = toWorkflowTemplate(customerSupport)

    expect(tpl.steps.map((s) => s.id)).toEqual(['input', 'intent', 'kb'])
    expect(tpl.steps[1]).toMatchObject({
      type: 'llm', componentId: 'llm', label: 'Intent Classifier', position: { x: 340, y: 80 },
      config: { temperature: 0.3 },
    })
    expect(tpl.steps[2].category).toBe('vector')
    expect(tpl.connections).toEqual([
      expect.objectContaining({ sourceId: 'input', targetId: 'intent', isErrorPath: false }),
      expect.objectContaining({ sourceId: 'intent', targetId: 'kb', condition: "${intent} != 'general'" }),
    ])
    expect(tpl.unsupportedNodeTypes).toEqual(['input', 'llm', 'vector-search'])
  })

  it('sem passos e sem gêmeo local: lista vazia e nenhuma marcação inventada', () => {
    const tpl = toWorkflowTemplate({ ...customerSupport, steps: undefined, unsupportedNodeTypes: [] })

    expect(tpl.steps).toEqual([])
    expect(tpl.unsupportedNodeTypes).toBeUndefined()
  })

  it('com gêmeo local e sem passos no backend: o grafo local fica, a marcação do backend não', () => {
    const tpl = toWorkflowTemplate({
      id: 'sac-basic', displayName: 'SAC do servidor', description: 'resumo do servidor', category: 'x',
      steps: [], unsupportedNodeTypes: [],
    })

    expect(tpl.name).toBe('SAC do servidor')
    expect(tpl.steps).toHaveLength(7)
    expect(tpl.connections).toHaveLength(8)
    expect(tpl.unsupportedNodeTypes).toBeUndefined()
  })
})

describe('mergeCatalogs', () => {
  it('junta sem repetir id e o backend vence', () => {
    const local = bundledList()
    const server = { ...local[0], name: 'Versão do servidor' }
    const merged = mergeCatalogs(local, [server, toWorkflowTemplate(customerSupport)])

    expect(merged).toHaveLength(local.length + 1)
    expect(merged.find((t) => t.id === local[0].id)?.name).toBe('Versão do servidor')
  })
})

describe('templateApi.list', () => {
  it('com o backend no ar, a galeria mostra os templates dele E os 6 locais, todos marcados', async () => {
    apiMock.get.mockResolvedValue([customerSupport])
    apiMock.post.mockImplementation(async (_path: string, body: Record<string, unknown[]>) =>
      Object.fromEntries(Object.keys(body).map((id) => [id, id === 'sac-basic' ? ['agent', 'tool'] : []])))

    const list = await templateApi.list()

    const ids = list.map((t) => t.id)
    expect(ids).toEqual(expect.arrayContaining([
      'customer-support', 'sac-basic', 'rag-doc-qa', 'code-reviewer', 'content-writer', 'web-research',
      'email-triage',
    ]))
    expect(ids).toHaveLength(7)
    expect(list.find((t) => t.id === 'customer-support')?.steps).toHaveLength(3)
    expect(list.find((t) => t.id === 'customer-support')?.unsupportedNodeTypes)
      .toEqual(['input', 'llm', 'vector-search'])
    expect(list.find((t) => t.id === 'sac-basic')?.unsupportedNodeTypes).toEqual(['agent', 'tool'])

    // Só os locais vão para a verificação (os do backend já vêm marcados), no documento que o motor lê.
    expect(apiMock.post).toHaveBeenCalledTimes(1)
    const [path, body] = apiMock.post.mock.calls[0]
    expect(path).toBe('/templates/unsupported-node-types')
    expect(Object.keys(body)).not.toContain('customer-support')
    expect(body['sac-basic'][1]).toEqual({
      id: 's2', type: 'condition', componentId: 'condition', configuration: { strategy: 'llm' },
    })
    expect(templateApi.get('code-reviewer')).toBeDefined()
  })

  it('sem backend: o catálogo local inteiro, sem afirmar nada sobre executores', async () => {
    apiMock.get.mockRejectedValue(new Error('502'))
    apiMock.post.mockRejectedValue(new Error('502'))

    const list = await templateApi.list()

    expect(list).toHaveLength(bundledList().length)
    expect(list.every((t) => t.unsupportedNodeTypes === undefined)).toBe(true)
  })
})

describe('templateApi.list — verificação que falhou', () => {
  it('não fica no cache: a chamada seguinte pergunta de novo e marca', async () => {
    apiMock.get.mockResolvedValue([])
    apiMock.post.mockRejectedValueOnce(new Error('503'))
    const first = await templateApi.list()
    expect(first.every((t) => t.unsupportedNodeTypes === undefined)).toBe(true)

    apiMock.post.mockImplementation(async (_p: string, body: Record<string, unknown[]>) =>
      Object.fromEntries(Object.keys(body).map((id) => [id, ['agent']])))
    const second = await templateApi.list()

    expect(apiMock.post).toHaveBeenCalledTimes(2)
    expect(second.every((t) => Array.isArray(t.unsupportedNodeTypes))).toBe(true)
  })
})

/**
 * RCHFLW-45: o clone gravava o formato do designer (config em `config`, arestas num
 * `configuration.connections` no topo). O editor e o motor só leem `steps[].configuration` e
 * `steps[].connections`: o clone abria com os nós soltos e o primeiro Salvar apagava a config.
 */
describe('templateApi.clone', () => {
  beforeEach(() => {
    workflowApiMock.create.mockImplementation(async (payload: unknown) => ({ id: 'wf-1', ...(payload as object) }))
  })

  it('template local (sac-basic): arestas dentro dos passos e config em configuration', async () => {
    await templateApi.clone('sac-basic')

    const payload = workflowApiMock.create.mock.calls[0][0]
    expect(payload.configuration).not.toHaveProperty('connections')
    expect(payload.configuration).toMatchObject({ templateId: 'sac-basic' })
    expect(payload.steps).toHaveLength(7)
    for (const step of payload.steps) expect(step).not.toHaveProperty('config')
    const router = payload.steps.find((s: { id: string }) => s.id === 's2')
    expect(router).toMatchObject({
      type: 'condition', componentId: 'condition', label: 'Intent router', position: { x: 280, y: 200 },
      configuration: { strategy: 'llm' },
    })
    expect(router.connections.map((c: { targetId: string }) => c.targetId)).toEqual(['s3', 's4', 's5'])
    // O que o editor lê de volta: as 8 arestas do template.
    expect(connectionsFromSteps(payload.steps)).toHaveLength(8)
  })

  it('template do backend: a condição da aresta e o type cru sobrevivem', async () => {
    apiMock.get.mockResolvedValue([customerSupport])
    apiMock.post.mockResolvedValue({})
    await templateApi.list()

    await templateApi.clone('customer-support')

    const payload = workflowApiMock.create.mock.calls[0][0]
    expect(payload.steps.map((s: { type: string }) => s.type)).toEqual(['input', 'llm', 'vector-search'])
    expect(payload.steps[1].configuration).toEqual({ temperature: 0.3 })
    expect(payload.steps[1].connections).toEqual([
      { sourceId: 'intent', targetId: 'kb', isErrorPath: false, condition: "${intent} != 'general'" },
    ])
    expect(payload.steps[2].connections).toEqual([])
  })
})
