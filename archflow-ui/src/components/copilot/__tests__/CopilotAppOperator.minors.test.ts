/**
 * Ajustes menores do copiloto (RCHFLW-189, 2º bug hunt de QA).
 *
 * <ul>
 *   <li>(3) Os exemplos de ramificação terminavam na aprovação, e o fluxo gerado também: o pedido aprovado terminava
 *       COMPLETED com {"decision":"APPROVED"}, sem resposta ao cliente.</li>
 *   <li>(4) O showStatus — que o modelo usa como resposta final — só aparecia no toast: o chat lateral ficava com a
 *       pergunta do usuário e nada mais.</li>
 * </ul>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act, type ComponentType } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

type Tool = { name: string; followUp?: boolean; render?: ComponentType<{ args: Record<string, unknown> }> }

const tools = vi.hoisted(() => new Map<string, Tool>())
const contexts = vi.hoisted(() => new Map<string, unknown>())

vi.mock('@copilotkit/react-core/v2', () => ({
  useAgentContext: ({ description, value }: { description: string; value: unknown }) => {
    contexts.set(description, value)
  },
  useFrontendTool: (tool: Tool) => { tools.set(tool.name, tool) },
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn(), hide: vi.fn(), update: vi.fn() } }))
vi.mock('../../../services/api', () => ({
  api: { post: vi.fn() },
  workflowApi: { list: vi.fn(async () => []), create: vi.fn() },
  executionApi: {},
}))
vi.mock('../../../services/knowledge-api', () => ({ knowledgeApi: { listBases: vi.fn(async () => []) } }))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))

import CopilotAppOperator from '../CopilotAppOperator'

const roots: Root[] = []
const containers: HTMLElement[] = []

async function mount(element: ReturnType<typeof h>) {
  const container = document.createElement('div')
  document.body.appendChild(container)
  containers.push(container)
  const root = createRoot(container)
  roots.push(root)
  await act(async () => { root.render(element) })
  return container
}

beforeEach(() => {
  tools.clear()
  contexts.clear()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
})

afterEach(async () => {
  for (const r of roots.splice(0)) await act(async () => { r.unmount() })
  for (const c of containers.splice(0)) c.remove()
})

const rules = () => [...contexts.entries()].find(([d]) => d.startsWith('How to classify and branch'))?.[1] as string[]

describe('RCHFLW-189 (3): a aprovação não é o fim do fluxo', () => {
  it('as regras dizem que aprovar retoma o fluxo e pedem o passo de resposta depois da aprovação', async () => {
    await mount(h(MemoryRouter, { initialEntries: ['/editor/wf-1'] }, h(CopilotAppOperator)))

    const all = rules().join('\n')
    expect(all).toContain('An approval is not the end of the flow: approving RESUMES the run at the next node')
    expect(all).toContain('Without it an approved request never gets an answer')
    // Os dois exemplos que terminavam na aprovação agora seguem dela para a resposta.
    expect(all).toContain('and approval -> a second llm-chat that answers the approved request')
    expect(all).toContain('then approval -> an llm-chat that answers the approved refund')
  })

  it('o pedido do "Gerar com IA" pede a resposta depois da aprovação, nos dois idiomas', () => {
    const locale = (f: string) => JSON.parse(readFileSync(resolve(__dirname, `../../../i18n/locales/${f}`), 'utf-8'))
    expect(locale('pt-BR.json').editor.ai.promptTemplate)
      .toContain('A aprovação não é o fim do fluxo: aprovar retoma a execução no nó seguinte')
    expect(locale('en.json').editor.ai.promptTemplate)
      .toContain('An approval is not the end of the flow: approving resumes the run at the next node')
  })
})

describe('RCHFLW-189 (4): o showStatus aparece no chat', () => {
  it('a ferramenta registra uma renderização que mostra a mensagem na conversa', async () => {
    await mount(h(MemoryRouter, { initialEntries: ['/editor/wf-1'] }, h(CopilotAppOperator)))

    const status = tools.get('showStatus')!
    expect(status.followUp).toBe(false)
    expect(status.render).toBeTypeOf('function')
    const chat = await mount(h(status.render!, { args: { message: 'Limite alterado para R$ 500 na condição.' } }))
    expect(chat.querySelector('[data-testid="copilot-status-message"]')?.textContent)
      .toBe('Limite alterado para R$ 500 na condição.')
  })

  it('sem mensagem (argumentos ainda chegando), nada é desenhado', async () => {
    await mount(h(MemoryRouter, { initialEntries: ['/editor/wf-1'] }, h(CopilotAppOperator)))

    const chat = await mount(h(tools.get('showStatus')!.render!, { args: {} }))
    expect(chat.querySelector('[data-testid="copilot-status-message"]')).toBeNull()
  })
})
