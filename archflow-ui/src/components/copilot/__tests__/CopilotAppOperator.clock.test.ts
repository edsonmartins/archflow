/**
 * RCHFLW-98 — a entrada de data e hora chega ao agente porque o CopilotAppOperator, montado uma vez dentro do
 * provider, monta o CopilotClockContext. Sem isso o componente existiria e o copiloto continuaria sem a data.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'

const contexts = vi.hoisted(() => new Map<string, unknown[]>())

// Mesmo mock de CopilotAppOperator.configure.test.ts, mas contando quantas vezes cada descrição foi registrada.
vi.mock('@copilotkit/react-core/v2', () => ({
  useAgentContext: ({ description, value }: { description: string; value: unknown }) => {
    contexts.set(description, [...(contexts.get(description) ?? []), value])
  },
  useFrontendTool: () => {},
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../confirmCopilotRemoval', () => ({ confirmCopilotNodeRemoval: vi.fn() }))
vi.mock('../../../services/api', () => ({
  api: { post: vi.fn() },
  workflowApi: { list: vi.fn(async () => []), create: vi.fn() },
  executionApi: {},
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))

import CopilotAppOperator from '../CopilotAppOperator'

let root: Root | undefined
let container: HTMLElement | undefined

beforeEach(() => {
  contexts.clear()
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
})

afterEach(() => {
  act(() => root?.unmount())
  container?.remove()
})

describe('CopilotAppOperator + relógio', () => {
  it('registra uma única entrada de data e hora atuais', () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    act(() => root!.render(h(MemoryRouter, null, h(CopilotAppOperator))))

    const clockEntries = [...contexts.keys()].filter(d => d.includes('current local date and time'))
    expect(clockEntries).toHaveLength(1)
    const value = contexts.get(clockEntries[0])!.at(-1) as { now: string; timeZone: string; weekday: string }
    expect(value.now).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}[+-]\d{2}:\d{2}$/)
    expect(value.timeZone).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone)
  })
})
