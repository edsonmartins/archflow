/**
 * QA2 RCHFLW-158: as escritas das bases de conhecimento — criar base, enviar arquivo (upload em
 * `/api/files` + ingestão) e voltar a versão do índice — exigem `workflow:update` no servidor. Quem não a
 * tem segue lendo e buscando (a busca é um POST que só lê), mas não vê oferecida uma escrita que vira 403.
 *
 * <p>A tabela é a real: no jsdom ela desenha só o espelho acessível, em que cada ação visível é um botão.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor } from '@testing-library/dom'

const knowledgeApiMock = vi.hoisted(() => ({
  listBases: vi.fn(), createBase: vi.fn(), listDocuments: vi.fn(), getFile: vi.fn(),
  upload: vi.fn(), ingest: vi.fn(), getJob: vi.fn(), search: vi.fn(), versions: vi.fn(),
  rollback: vi.fn(),
}))
// t estável: loadDetail depende dele, e um t novo a cada render recarregaria em laço.
const i18n = vi.hoisted(() => ({
  t: (k: string, o?: { defaultValue?: string }) => o?.defaultValue ?? k,
  i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
}))
vi.mock('react-i18next', () => ({ useTranslation: () => i18n }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../services/knowledge-api', () => ({ knowledgeApi: knowledgeApiMock }))

import KnowledgeBasesPage from '../KnowledgeBasesPage'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  knowledgeApiMock.listBases.mockResolvedValue([
    { id: 'kb-1', tenantId: 't', workspaceId: null, name: 'Políticas', createdAt: '2026-11-25T09:00:00Z' },
  ])
  knowledgeApiMock.listDocuments.mockResolvedValue([])
  knowledgeApiMock.versions.mockResolvedValue([
    { id: 'v-2', baseId: 'kb-1', basedOnVersionId: 'v-1', status: 'ACTIVE', createdAt: '', activatedAt: '' },
    { id: 'v-1', baseId: 'kb-1', basedOnVersionId: null, status: 'INACTIVE', createdAt: '', activatedAt: '' },
  ])
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(MemoryRouter, null, h(KnowledgeBasesPage))))
  })
  await waitFor(() => expect(knowledgeApiMock.versions).toHaveBeenCalled())
  // As versões ficam na própria aba.
  const aba = await waitFor(() => {
    const tab = [...container.querySelectorAll('[role="tab"]')].find(t => t.textContent?.includes('knowledge.versions'))
    expect(tab).toBeTruthy()
    return tab as HTMLElement
  })
  await act(async () => { fireEvent.click(aba) })
  await waitFor(() => expect(container.querySelector('[data-testid="knowledge-version-v-1"]')).not.toBeNull())
}

function botao(testId: string): HTMLButtonElement {
  return container.querySelector(`[data-testid="${testId}"]`) as HTMLButtonElement
}

function falta(testId: string): string | null {
  return botao(testId).closest('[data-permission-missing]')?.getAttribute('data-permission-missing') ?? null
}

function temRollback(): boolean {
  const linha = container.querySelector('[data-testid="knowledge-version-v-1"]')!
  return [...linha.querySelectorAll('button')].some(b => b.textContent === 'knowledge.rollback')
}

describe('KnowledgeBasesPage — escritas exigem workflow:update (QA2 RCHFLW-158)', () => {
  for (const papel of ['VIEWER', 'EXECUTOR']) {
    it(`${papel}: Nova base e Enviar arquivo desabilitados com o aviso; sem voltar versão`, async () => {
      entrarComo(papel)
      await render()

      expect(botao('knowledge-new-base').disabled).toBe(true)
      expect(falta('knowledge-new-base')).toBe('workflow:update')
      expect(botao('knowledge-upload').disabled).toBe(true)
      expect(falta('knowledge-upload')).toBe('workflow:update')
      expect(temRollback()).toBe(false)
    })
  }

  it('DESIGNER: tudo habilitado, sem aviso, e a versão inativa pode voltar', async () => {
    entrarComo('DESIGNER')
    await render()

    expect(botao('knowledge-new-base').disabled).toBe(false)
    expect(falta('knowledge-new-base')).toBeNull()
    expect(botao('knowledge-upload').disabled).toBe(false)
    expect(temRollback()).toBe(true)
  })
})
