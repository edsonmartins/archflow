/**
 * RCHFLW-55 / RCHFLW-56 — nós de dados no painel: o seletor de Base de conhecimento (vector-search e rag) e as
 * chaves que o painel grava para o vector store, que o archflow-api traduz para as do adapter
 * (DesignerAdapterKeysConformanceTest lê o VectorStoreFields.tsx do outro lado).
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, within } from '@testing-library/dom'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o && 'value' in o ? `${k}:${String(o.value)}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

type Item = { value: string; label: string }

// Select e NumberInput nativos: o que importa é o que aparece e o que o onChange grava.
vi.mock('@mantine/core', async (importOriginal) => {
  const real = await importOriginal<typeof import('@mantine/core')>()
  const { createElement } = await import('react')
  const Select = (props: {
    label?: string; value?: string | null; data?: Item[]; onChange?: (v: string | null) => void
  }) => createElement('label', null, props.label,
    createElement('select', {
      'data-label': props.label,
      value: props.value ?? '',
      onChange: (e: { target: { value: string } }) => props.onChange?.(e.target.value === '' ? null : e.target.value),
    }, [createElement('option', { key: '', value: '' }, '—'),
      ...(props.data ?? []).map(i => createElement('option', { key: i.value, value: i.value }, i.label))]))
  const NumberInput = (props: {
    label?: string; value?: number | string; placeholder?: string; onChange?: (v: number | string) => void
  }) => createElement('input', {
    'data-label': props.label,
    value: props.value ?? '',
    placeholder: props.placeholder,
    onChange: (e: { target: { value: string } }) =>
      props.onChange?.(e.target.value === '' ? '' : Number(e.target.value)),
  })
  return { ...real, Select, NumberInput }
})

const listBases = vi.fn()
vi.mock('../../../services/knowledge-api', () => ({ knowledgeApi: { listBases: () => listBases() } }))

vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({
    providers: [{ id: 'openai', displayName: 'OpenAI', group: 'Cloud', models: [] }],
    patterns: [], personas: [], governance: [], mcpServers: [], loading: false,
  }),
}))
vi.mock('../useChatProviders', () => ({ useChatProviders: () => null }))
vi.mock('../useCatalog', () => ({
  useCatalog: () => ({
    agents: [], assistants: [], tools: [], embeddings: [],
    memories: [{ id: 'redis', displayName: 'Redis' }, { id: 'jdbc', displayName: 'JDBC' }],
    vectorstores: [], chains: [], usingFallback: false,
  }),
}))

const updateNodeConfig = vi.fn()
vi.mock('../../FlowCanvas/store/useFlowStore', () => ({
  useFlowStore: () => ({ updateNodeConfig, updateNodeLabel: vi.fn(), executionState: {}, nodes: [], edges: [] }),
}))

import { PropertyPanel } from '../../PropertyPanel'
import { KnowledgeBaseField } from '../fields/KnowledgeBaseField'
import { VectorStoreFields } from '../fields/VectorStoreFields'
import { ShapedNode } from '../../FlowCanvas/nodes/ShapedNode'
import { ReactFlowProvider } from '@xyflow/react'
import { runBlockers } from '../../FlowCanvas/runReadiness'
import type { FlowNodeData } from '../../FlowCanvas/types'

class ResizeObserverStub { observe() {} unobserve() {} disconnect() {} }

let root: Root | null = null
let container: HTMLDivElement

function data(nodeType: string, config: Record<string, unknown> = {}): FlowNodeData {
  return { label: 'Nó', componentId: nodeType, nodeType, status: 'idle', config } as FlowNodeData
}

async function mount(element: ReturnType<typeof h>) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root!.render(h(MantineProvider, null, element)) })
  // A lista de bases chega por uma promise: deixa o efeito terminar.
  await act(async () => { await Promise.resolve() })
  return within(container)
}

const select = (label: string) =>
  container.querySelector(`select[data-label="editor.properties.fields.${label}"]`) as HTMLSelectElement | null
const number = (label: string) =>
  container.querySelector(`input[data-label="editor.properties.fields.${label}"]`) as HTMLInputElement | null

beforeEach(() => {
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  if (!(document as any).fonts) {
    Object.defineProperty(document, 'fonts', {
      value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
    })
  }
  listBases.mockReset()
  listBases.mockResolvedValue([
    { id: 'kb-1', tenantId: 't', workspaceId: null, name: 'suporte', createdAt: '' },
    { id: 'kb-2', tenantId: 't', workspaceId: null, name: 'vendas', createdAt: '' },
  ])
  updateNodeConfig.mockReset()
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

describe('KnowledgeBaseField (RCHFLW-55)', () => {
  it('lista as bases do workspace e grava knowledgeBaseId; limpar tira a chave', async () => {
    const update = vi.fn()
    await mount(h(KnowledgeBaseField, { nodeData: data('vector-search'), update }))

    const s = select('knowledgeBase')!
    expect(Array.from(s.options).map(o => o.textContent)).toEqual(['—', 'suporte', 'vendas'])
    fireEvent.change(s, { target: { value: 'kb-1' } })
    expect(update).toHaveBeenCalledWith('knowledgeBaseId', 'kb-1')
    fireEvent.change(s, { target: { value: '' } })
    expect(update).toHaveBeenLastCalledWith('knowledgeBaseId', undefined)
  })

  it('base gravada que o workspace não tem é avisada, e continua visível no seletor', async () => {
    const view = await mount(h(KnowledgeBaseField, { nodeData: data('rag', { knowledgeBaseId: 'kb-sumiu' }), update: vi.fn() }))

    expect(select('knowledgeBase')!.value).toBe('kb-sumiu')
    expect(view.getByTestId('knowledge-base-missing').textContent)
      .toBe('editor.properties.fields.knowledgeBaseMissing:kb-sumiu')
  })

  it('nó de template antigo (vectorStoreId) mostra a base, e trocar grava a chave nova e tira a velha', async () => {
    const update = vi.fn()
    await mount(h(KnowledgeBaseField, { nodeData: data('vector-search', { vectorStoreId: 'kb-2' }), update }))

    expect(select('knowledgeBase')!.value).toBe('kb-2')
    fireEvent.change(select('knowledgeBase')!, { target: { value: 'kb-1' } })
    expect(update).toHaveBeenCalledWith('knowledgeBaseId', 'kb-1')
    expect(update).toHaveBeenCalledWith('vectorStoreId', undefined)
  })

  it('base gravada continua no seletor enquanto a lista carrega e quando ela falha — nunca "Nenhuma"', async () => {
    listBases.mockReturnValue(new Promise(() => {}))
    await mount(h(KnowledgeBaseField, { nodeData: data('vector-search', { knowledgeBaseId: 'kb-1' }), update: vi.fn() }))
    expect(select('knowledgeBase')!.value).toBe('kb-1')

    await act(async () => { root?.unmount() }); container.remove()
    listBases.mockRejectedValue(new Error('403'))
    const view = await mount(h(KnowledgeBaseField, { nodeData: data('rag', { knowledgeBaseId: 'kb-1' }), update: vi.fn() }))
    expect(select('knowledgeBase')!.value).toBe('kb-1')
    expect(view.getByTestId('knowledge-base-load-error')).toBeTruthy()
    // Sem a lista não se sabe se a base existe: não é "base inexistente".
    expect(view.queryByTestId('knowledge-base-missing')).toBeNull()
  })

  it('falha ao listar é dita, sem esconder o campo', async () => {
    listBases.mockRejectedValue(new Error('403'))
    const view = await mount(h(KnowledgeBaseField, { nodeData: data('vector-search'), update: vi.fn() }))

    expect(view.getByTestId('knowledge-base-load-error')).toBeTruthy()
    expect(select('knowledgeBase')).not.toBeNull()
  })
})

describe('PropertyPanel — nós de dados com e sem base', () => {
  it('vector-search com base: sem backend de vetores, topK com o padrão real da busca (5)', async () => {
    await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('vector-search', { knowledgeBaseId: 'kb-1' }) }))

    expect(select('vectorBackend')).toBeNull()
    expect(number('topK')!.placeholder).toBe('editor.properties.fields.defaultValue:5')
  })

  it('vector-search sem base: backend de vetores, e topK só com exemplo', async () => {
    await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('vector-search') }))

    expect(Array.from(select('vectorBackend')!.options).map(o => o.value))
      .toEqual(['', 'pgvector', 'pinecone', 'redis'])
    expect(number('topK')!.placeholder).toBe('editor.properties.fields.exampleValue:5')
  })

  it('rag: padrões do caminho certo — base (5 / 0) ou RagChainAdapter (2 / 0.7)', async () => {
    await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('rag', { knowledgeBaseId: 'kb-1' }) }))
    expect(select('embeddingProvider')).toBeNull()
    expect(number('topKRetrieval')!.placeholder).toBe('editor.properties.fields.defaultValue:5')
    expect(number('minScore')!.placeholder).toBe('editor.properties.fields.defaultValue:0')

    await act(async () => { root?.unmount() }); container.remove()
    await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('rag') }))
    expect(select('embeddingProvider')).not.toBeNull()
    expect(select('vectorStore')).not.toBeNull()
    expect(number('topKRetrieval')!.placeholder).toBe('editor.properties.fields.defaultValue:2')
    expect(number('minScore')!.placeholder).toBe('editor.properties.fields.defaultValue:0.7')
  })

  it('escolher a base no painel grava knowledgeBaseId no nó', async () => {
    await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('rag') }))

    fireEvent.change(select('knowledgeBase')!, { target: { value: 'kb-2' } })
    expect(updateNodeConfig).toHaveBeenCalledWith('n1', 'knowledgeBaseId', 'kb-2')
  })

  it('memória redis pede a conexão do servidor; JDBC avisa que não roda', async () => {
    const view = await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('memory', { memoryBackend: 'redis' }) }))
    fireEvent.change(view.getByLabelText(/editor\.properties\.fields\.serverConnection/), { target: { value: 'cache' } })
    expect(updateNodeConfig).toHaveBeenCalledWith('n1', 'redisConnection', 'cache')

    await act(async () => { root?.unmount() }); container.remove()
    const jdbc = await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('memory', { memoryBackend: 'jdbc' }) }))
    expect(jdbc.getByTestId('memory-jdbc-not-connected')).toBeTruthy()
  })

  it('RCHFLW-115: a memória tem o campo Operação (add/get/clear) e grava em operation', async () => {
    // Antes: só um aviso de que o painel não tinha o campo, e o nó nunca executava.
    await mount(h(PropertyPanel, { nodeId: 'n1',
      nodeData: data('memory', { memoryBackend: 'redis', redisConnection: 'cache' }) }))
    const op = select('memoryOperation')!
    expect(op).not.toBeNull()
    expect(Array.from(op.options).map(o => o.value).filter(Boolean)).toEqual(['add', 'get', 'clear'])
    expect(op.value).toBe('')
    expect(container.querySelector('[data-testid="memory-needs-operation"]')).toBeNull()
    // O Papel só aparece no add.
    expect(select('memoryRole')).toBeNull()
    fireEvent.change(op, { target: { value: 'get' } })
    expect(updateNodeConfig).toHaveBeenCalledWith('n1', 'operation', 'get')
  })

  it('RCHFLW-115: no add, o Papel da mensagem (user/ai/system) grava em memoryRole', async () => {
    await mount(h(PropertyPanel, { nodeId: 'n1',
      nodeData: data('memory', { memoryBackend: 'redis', redisConnection: 'cache', operation: 'add' }) }))
    expect(select('memoryOperation')!.value).toBe('add')
    const role = select('memoryRole')!
    expect(Array.from(role.options).map(o => o.value).filter(Boolean)).toEqual(['user', 'ai', 'system'])
    fireEvent.change(role, { target: { value: 'ai' } })
    expect(updateNodeConfig).toHaveBeenCalledWith('n1', 'memoryRole', 'ai')
  })

  it('Vector store sem operation diz que não grava; Vector search não', async () => {
    const store = await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('vector-store', { vectorBackend: 'pgvector' }) }))
    expect(store.getByTestId('vector-store-does-not-write').textContent)
      .toBe('editor.properties.fields.vectorStoreDoesNotWrite')

    await act(async () => { root?.unmount() }); container.remove()
    const search = await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('vector-search', { vectorBackend: 'pgvector' }) }))
    expect(search.queryByTestId('vector-store-does-not-write')).toBeNull()
  })

  it('embedding local avisa que exige caminhos que o painel não tem, no Embedding e no RAG', async () => {
    const emb = await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('embedding', { embeddingProvider: 'local' }) }))
    expect(emb.getByTestId('local-embedding-not-configurable')).toBeTruthy()

    await act(async () => { root?.unmount() }); container.remove()
    const rag = await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('rag', { embeddingProvider: 'local' }) }))
    expect(rag.getByTestId('local-embedding-not-configurable')).toBeTruthy()

    await act(async () => { root?.unmount() }); container.remove()
    const openai = await mount(h(PropertyPanel, { nodeId: 'n1', nodeData: data('embedding', { embeddingProvider: 'openai' }) }))
    expect(openai.queryByTestId('local-embedding-not-configurable')).toBeNull()
  })
})

describe('VectorStoreFields (RCHFLW-56) — chaves gravadas', () => {
  const backends = [{ value: 'pgvector', label: 'pgvector' }, { value: 'redis', label: 'redis' },
    { value: 'pinecone', label: 'pinecone' }]

  it('pgvector grava o NOME da conexão, a tabela e a dimensão — nenhum campo de senha ou URL', async () => {
    const update = vi.fn()
    const view = await mount(h(VectorStoreFields, { nodeData: data('vector-search', { vectorBackend: 'pgvector' }), update, backends }))

    const conn = view.getByLabelText(/editor\.properties\.fields\.serverConnection/) as HTMLInputElement
    expect(container.textContent).toContain('editor.properties.fields.serverConnectionHint:archflow.adapters.pgvector.<nome>.*')
    fireEvent.change(conn, { target: { value: 'principal' } })
    expect(update).toHaveBeenCalledWith('pgDatasource', 'principal')
    fireEvent.change(view.getByLabelText(/editor\.properties\.fields\.tableName/), { target: { value: 'docs' } })
    expect(update).toHaveBeenCalledWith('pgTableName', 'docs')
    expect((view.getByLabelText(/editor\.properties\.fields\.tableName/) as HTMLInputElement).placeholder)
      .toBe('editor.properties.fields.defaultValue:embeddings')
    fireEvent.change(number('dimensions')!, { target: { value: '1536' } })
    expect(update).toHaveBeenCalledWith('pgDimensions', 1536)
    expect(container.querySelector('input[type="password"]')).toBeNull()
  })

  it('redis e pinecone: conexão por nome e vectorDimensions; sem URL do Redis nem chave do Pinecone', async () => {
    const update = vi.fn()
    const view = await mount(h(VectorStoreFields, { nodeData: data('vector-search', { vectorBackend: 'redis' }), update, backends }))
    fireEvent.change(view.getByLabelText(/editor\.properties\.fields\.serverConnection/), { target: { value: 'cache' } })
    expect(update).toHaveBeenCalledWith('redisConnection', 'cache')
    fireEvent.change(number('dimensions')!, { target: { value: '128' } })
    expect(update).toHaveBeenCalledWith('vectorDimensions', 128)
    expect(container.textContent).not.toContain('editor.properties.fields.redisUrl')

    await act(async () => { root?.unmount() }); container.remove()
    const pc = await mount(h(VectorStoreFields, { nodeData: data('vector-search', { vectorBackend: 'pinecone' }), update, backends }))
    fireEvent.change(pc.getByLabelText(/editor\.properties\.fields\.serverConnection/), { target: { value: 'pc' } })
    expect(update).toHaveBeenCalledWith('pineconeConnection', 'pc')
    expect(container.textContent).not.toContain('editor.properties.fields.pineconeApiKey')
    expect(container.querySelector('input[type="password"]')).toBeNull()
  })
})

describe('canvas e Executar só contam o que o motor usa', () => {
  /** Os chips do card do canvas, na ordem. */
  async function chips(node: FlowNodeData): Promise<string[]> {
    await act(async () => { root?.unmount() }); container?.remove()
    await mount(h(ReactFlowProvider, null, h(ShapedNode, { id: 'n', data: node, selected: false, category: 'vector' })))
    return Array.from(container.querySelectorAll('[data-testid="node-chip"]')).map(c => c.textContent ?? '')
  }

  it('chip do backend só com a conexão; com base, a base no lugar do backend', async () => {
    expect(await chips(data('vector-search', { vectorBackend: 'pgvector' }))).toEqual([])
    expect(await chips(data('vector-search', { vectorBackend: 'pgvector', pgDatasource: 'principal' })))
      .toEqual(['pgvector · principal'])
    expect(await chips(data('vector-search', { vectorBackend: 'pgvector', knowledgeBaseId: 'kb-1', topK: 3 })))
      .toEqual(['kb kb-1', 'top-3'])
    expect(await chips(data('rag', { knowledgeBaseId: 'kb-1', embeddingProvider: 'openai', llmProvider: 'openai' })))
      .toEqual(['kb kb-1', 'openai'])
    expect(await chips(data('memory', { memoryBackend: 'jdbc' }))).toEqual([])
  })

  it('o Executar pede conexão e dimensão do vector store, e nada quando há base', () => {
    const step = (componentId: string, config: Record<string, unknown>) => ({ id: componentId, label: componentId, componentId, config })
    expect(runBlockers([step('vector-search', { vectorBackend: 'pgvector' })]).missing.map(m => m.labelKey)).toEqual([
      'editor.properties.fields.serverConnection', 'editor.properties.fields.dimensions',
    ])
    expect(runBlockers([step('vector-search', { knowledgeBaseId: 'kb-1' })]).missing).toEqual([])
    expect(runBlockers([step('rag', {})]).missing.map(m => m.labelKey)).toEqual([
      'editor.properties.fields.embeddingProvider', 'editor.properties.fields.vectorStore',
      'editor.properties.fields.llmProvider',
    ])
    expect(runBlockers([step('rag', { knowledgeBaseId: 'kb-1' })]).missing).toEqual([])
    expect(runBlockers([step('memory', { memoryBackend: 'redis' })]).missing.map(m => m.labelKey))
      .toEqual(['editor.properties.fields.memoryOperation', 'editor.properties.fields.serverConnection'])
  })

  it('memória e Vector store sem operation não rodam: o Executar diz o campo, como o motor', () => {
    const step = (componentId: string, config: Record<string, unknown>) => ({ id: componentId, label: componentId, componentId, config })
    // Antes: memória vazia passava (o motor recusa sem backend) e redis com conexão passava (recusa sem operation).
    // RCHFLW-115: a Memória cobra o campo do painel (Operação), não mais o "o painel ainda não tem esse campo".
    expect(runBlockers([step('memory', {})]).missing.map(m => m.labelKey)).toEqual([
      'editor.properties.fields.memoryOperation', 'editor.properties.fields.memoryBackend',
    ])
    expect(runBlockers([step('memory', { memoryBackend: 'redis', redisConnection: 'cache' })]).missing
      .map(m => m.labelKey)).toEqual(['editor.properties.fields.memoryOperation'])
    expect(runBlockers([step('memory', { memoryBackend: 'redis', redisConnection: 'cache', operation: 'get' })])
      .missing).toEqual([])
    // O add guarda a entrada como mensagem: sem o Papel, o motor recusa — o Executar também.
    const add = { memoryBackend: 'redis', redisConnection: 'cache', operation: 'add' }
    expect(runBlockers([step('memory', add)]).missing.map(m => m.labelKey))
      .toEqual(['editor.properties.fields.memoryRole'])
    expect(runBlockers([step('memory', { ...add, memoryRole: 'user' })]).missing).toEqual([])
    // A operação no nível do nó (YAML/API) também conta para o Papel.
    expect(runBlockers([{ ...step('memory', { memoryBackend: 'redis', redisConnection: 'cache' }), operation: 'add' }])
      .missing.map(m => m.labelKey)).toEqual(['editor.properties.fields.memoryRole'])
    // Antes: o Vector store com backend, conexão e dimensão passava e o motor buscava em vez de gravar.
    const store = { vectorBackend: 'pgvector', pgDatasource: 'principal', pgDimensions: 1536 }
    expect(runBlockers([step('vector-store', store)]).missing.map(m => m.labelKey))
      .toEqual(['editor.properties.fields.operationNotInPanel'])
    expect(runBlockers([step('vector-store', { ...store, operation: 'search' })]).missing).toEqual([])
    expect(runBlockers([step('vector-search', store)]).missing).toEqual([])
  })

  it('o Executar aceita as chaves nativas que o motor aceita (nó escrito em YAML/API)', () => {
    const step = (componentId: string, config: Record<string, unknown>) => ({ id: componentId, label: componentId, componentId, config })
    expect(runBlockers([step('vector-search', {
      provider: 'pgvector', 'pgvector.connection': 'principal', 'pgvector.dimension': 1536,
    })]).missing).toEqual([])
    expect(runBlockers([step('vector-search', {
      provider: 'redis', 'redis.connection': 'cache', 'redis.dimension': 8,
    })]).missing).toEqual([])
    expect(runBlockers([step('rag', {
      'embedding.provider': 'openai', 'vectorstore.provider': 'pgvector', 'languagemodel.provider': 'openai',
      'pgvector.connection': 'principal', 'pgvector.dimension': 1536,
    })]).missing).toEqual([])
    expect(runBlockers([step('embedding', { provider: 'openai' })]).missing).toEqual([])
    expect(runBlockers([step('memory', { provider: 'redis', 'redis.connection': 'cache', operation: 'add',
      'memory.role': 'user' })]).missing).toEqual([])
    // O provider explícito vence o campo do designer (DesignerAdapterKeys.providerOf): conta a conexão dele.
    expect(runBlockers([step('vector-search', { provider: 'redis', vectorBackend: 'pgvector',
      pgDatasource: 'principal', pgDimensions: 1536 })]).missing.map(m => m.labelKey)).toEqual([
      'editor.properties.fields.serverConnection', 'editor.properties.fields.dimensions',
    ])
  })

  it('embedding local pede os caminhos que o adapter exige', () => {
    const step = (componentId: string, config: Record<string, unknown>) => ({ id: componentId, label: componentId, componentId, config })
    expect(runBlockers([step('embedding', { embeddingProvider: 'local', embeddingDimensions: 384 })]).missing
      .map(m => m.labelKey)).toEqual(['editor.properties.fields.localModelPath', 'editor.properties.fields.localVocabPath'])
    expect(runBlockers([step('embedding', { embeddingProvider: 'local', embeddingDimensions: 384,
      'local.model.path': '/m.onnx', 'local.vocab.path': '/v.txt' })]).missing).toEqual([])
    expect(runBlockers([step('rag', { embeddingProvider: 'local', vectorBackend: 'pgvector', pgDatasource: 'p',
      pgDimensions: 384, llmProvider: 'openai' })]).missing.map(m => m.labelKey)).toEqual([
      'editor.properties.fields.localModelPath', 'editor.properties.fields.localVocabPath',
      'editor.properties.fields.dimensions',
    ])
  })
})
