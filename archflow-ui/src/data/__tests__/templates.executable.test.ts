/**
 * Os templates rag-doc-qa e content-writer da galeria executam de ponta a ponta (RCHFLW-202).
 *
 * <p>Antes os dois eram recusados antes do primeiro passo: o rag-doc-qa tinha 'prompt-template' (sem executor, e
 * citando um template 'rag.qa' que não existe) e um 'embedding' sem provider; o content-writer tinha 'agent' e
 * 'parallel', com o papel numa chave 'role' que nada lia. Estes testes passam cada template pelo mesmo caminho do
 * "Usar este modelo" — `templateApi.clone` → o documento que o backend guarda → a leitura do editor
 * (`workflowDataFromDocument`) → a checagem do Executar (`runBlockers`) — e conferem os tipos contra
 * NOT_EXECUTABLE_NODE_TYPES, o espelho de NodesWithoutExecution do motor (NodesWithoutExecutionMirrorTest).</p>
 *
 * <p>Esse espelho não cobre tudo o que a galeria marca: o POST /templates/unsupported-node-types também conta o nó
 * que cai num componente que não existe (um vector-search sem base nem backend, por exemplo) e o card mostra "Nós sem
 * executor". Essa metade só o motor responde — GaleriaRefeitaSemNoSemExecutorTest (archflow-api) passa estas duas
 * receitas pelo mesmo endpoint, com o corpo que a galeria manda, e falha se algum nó vier marcado.</p>
 *
 * <p>A cópia em public/templates é a que a galeria carrega primeiro; a de src/data/templates.ts é o fallback sem rede.
 * As duas têm de dizer o mesmo, senão a correção vale só para um dos caminhos.</p>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const apiMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const workflowApiMock = vi.hoisted(() => ({ create: vi.fn() }))
vi.mock('../../services/api', () => ({ api: apiMock, workflowApi: workflowApiMock }))

import { getTemplate, type WorkflowTemplate } from '../templates'
import { templateApi } from '../../services/template-api'
import { workflowDataFromDocument, type DocumentStep } from '../../components/FlowCanvas/workflowSerde'
import { NOT_EXECUTABLE_NODE_TYPES, isNotExecutableNode } from '../../components/FlowCanvas/constants'
import { hasRunBlockers, runBlockers } from '../../components/FlowCanvas/runReadiness'
import { KNOWLEDGE_BASE_KEY } from '../../components/FlowCanvas/dataNodeKeys'

const IDS = ['rag-doc-qa', 'content-writer'] as const

function publicTemplate(id: string): WorkflowTemplate {
  return JSON.parse(readFileSync(resolve(__dirname, `../../../public/templates/${id}.json`), 'utf-8'))
}

/** O documento que o clone grava, capturado no POST /workflows. */
async function clonedDocument(id: string): Promise<{ steps: DocumentStep[] }> {
  workflowApiMock.create.mockImplementation(async (body: object) => ({ id: 'wf-1', ...body }))
  await templateApi.clone(id)
  return workflowApiMock.create.mock.calls.at(-1)![0]
}

/** A checagem do Executar sobre o documento, como a lista de fluxos a faz (WorkflowListPage.handleRunClick). */
function blockersOf(document: { steps: DocumentStep[] }) {
  const data = workflowDataFromDocument(document)
  return runBlockers(data.steps.map(s => ({
    id: s.id, label: s.label, componentId: s.componentId, type: s.type, config: s.config,
  })), data.connections)
}

function edgesOf(document: { steps: DocumentStep[] }) {
  return document.steps.flatMap(s => s.connections.map(c => `${c.sourceId}→${c.targetId}`))
}

beforeEach(() => {
  vi.clearAllMocks()
  templateApi._resetCache()
})

describe('templates refeitos só com nós que executam (RCHFLW-202)', () => {
  it.each(IDS)('%s: a cópia de public/templates e a do pacote são o mesmo template', (id) => {
    expect(publicTemplate(id)).toEqual(getTemplate(id))
  })

  it.each(IDS)('%s: clonado, nenhum nó é de tipo sem executor no motor', async (id) => {
    const doc = await clonedDocument(id)

    expect(doc.steps.length).toBeGreaterThan(0)
    for (const step of doc.steps) {
      expect(isNotExecutableNode(step), `${step.id} (${step.componentId})`).toBe(false)
      expect(NOT_EXECUTABLE_NODE_TYPES.has(step.type)).toBe(false)
    }
    // 'embedding' não está na lista, mas sem provider também não roda ("component not found"); o grafo não precisa
    // dele — a busca na base faz o próprio embedding da pergunta.
    expect(doc.steps.map(s => s.componentId)).not.toContain('embedding')
  })

  it.each(IDS)('%s: as arestas do template chegam dentro dos passos, numa linha só do início ao fim', async (id) => {
    const template = getTemplate(id)!
    const doc = await clonedDocument(id)

    expect(edgesOf(doc)).toEqual(template.connections.map(c => `${c.sourceId}→${c.targetId}`))
    const ids = doc.steps.map(s => s.id)
    expect(edgesOf(doc)).toEqual(ids.slice(0, -1).map((from, i) => `${from}→${ids[i + 1]}`))
    expect(doc.steps[0].componentId).toBe('input')
    expect(doc.steps.at(-1)!.componentId).toBe('output')
  })

  it('content-writer: cada LLM Chat tem o papel no systemPrompt e os dados na userMessage', async () => {
    const doc = await clonedDocument('content-writer')
    const chats = doc.steps.filter(s => s.componentId === 'llm-chat')

    expect(chats.length).toBeGreaterThan(0)
    for (const chat of chats) {
      expect(Object.keys(chat.configuration).sort()).toEqual(['systemPrompt', 'userMessage'])
      expect(String(chat.configuration.systemPrompt).trim()).not.toBe('')
      // O systemPrompt é mandado cru (RCHFLW-151): uma referência ali chegaria ao modelo como texto literal.
      expect(String(chat.configuration.systemPrompt)).not.toContain('${')
      // O texto da entrada vem sempre explícito: sem a referência ao input, o passo só via a saída do anterior.
      expect(String(chat.configuration.userMessage)).toContain('${s1}')
    }
  })

  it('content-writer: cada passo recebe o briefing e a saída do passo anterior', async () => {
    const doc = await clonedDocument('content-writer')
    const message = (id: string) => doc.steps.find(s => s.id === id)!.configuration.userMessage

    expect(doc.steps.map(s => s.componentId)).toEqual(['input', 'llm-chat', 'llm-chat', 'llm-chat', 'output'])
    expect(message('s2')).toBe('Briefing: ${s1}')
    expect(message('s3')).toBe('Briefing: ${s1}\n\nEsboço: ${s2}')
    expect(message('s4')).toBe('Briefing: ${s1}\n\nRascunho: ${s3}')
    expect(hasRunBlockers(blockersOf(doc))).toBe(false)
  })

  it('rag-doc-qa: o nó RAG busca na base e responde com a pergunta que recebeu da Entrada', async () => {
    const doc = await clonedDocument('rag-doc-qa')

    expect(doc.steps.map(s => s.componentId)).toEqual(['input', 'rag', 'output'])
    // Sem LLM Chat no meio: o KnowledgeNodeComponent busca com o texto da entrada e manda a mesma pergunta ao chat.
    // Uma busca separada (vector-search) sem base é marcada "sem executor" pela galeria — ver o comentário do topo.
    expect(doc.steps.map(s => s.componentId)).not.toContain('vector-search')
    // A chave que o painel grava no "Top K" do RAG (PropertyPanel → retrieverMaxResults), não o topK da busca.
    expect(doc.steps.find(s => s.id === 's2')!.configuration).toEqual({ retrieverMaxResults: 6 })
  })

  it('rag-doc-qa: a base fica para o usuário escolher; com ela, como o painel grava, nada impede o Executar',
    async () => {
      const doc = await clonedDocument('rag-doc-qa')

      // Sem base o RAG iria para o RagChainAdapter, com embedding, vector store e LLM que o template não configura:
      // o Executar pede a base (ou esses campos) no nó, e só nele.
      expect(doc.steps.find(s => s.id === 's2')!.configuration).not.toHaveProperty(KNOWLEDGE_BASE_KEY)
      const before = blockersOf(doc)
      expect(before.notExecutable).toEqual([])
      expect(before.invalidExpressions).toEqual([])
      expect(before.missing.length).toBeGreaterThan(0)
      expect(new Set(before.missing.map(m => m.id))).toEqual(new Set(['s2']))

      // KnowledgeBaseField grava só a chave da base no nó (update(KNOWLEDGE_BASE_KEY, id)).
      const chosen = {
        steps: doc.steps.map(s => s.id === 's2'
          ? { ...s, configuration: { ...s.configuration, [KNOWLEDGE_BASE_KEY]: 'kb-escolhida' } } : s),
      }
      expect(hasRunBlockers(blockersOf(chosen))).toBe(false)
    })
})
