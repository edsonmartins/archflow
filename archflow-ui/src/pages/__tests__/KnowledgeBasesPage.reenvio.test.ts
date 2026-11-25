/**
 * Reenvio do mesmo conteúdo na tela Conhecimento (RCHFLW-111). O servidor diz no `outcome` se o
 * conteúdo já estava na base ou se o documento que falhou foi reprocessado; antes a tela anunciava
 * sempre "Documento enviado para indexação", inclusive para um documento que nunca seria processado.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
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
const notify = vi.hoisted(() => vi.fn())

// t estável, como o do i18next: loadDetail depende dele, e um t novo a cada render o recriaria e
// dispararia o carregamento em laço.
const i18n = vi.hoisted(() => ({
  t: (k: string, o?: { defaultValue?: string }) => o?.defaultValue ?? k,
  i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
}))
vi.mock('react-i18next', () => ({ useTranslation: () => i18n }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: notify } }))
vi.mock('../../services/knowledge-api', () => ({ knowledgeApi: knowledgeApiMock }))
// A tabela real desenha em canvas, que o jsdom não tem.
vi.mock('../../components/VTable', () => ({
  VxTable: () => null,
  colunaApagada: (c: unknown) => c,
  colunaStatus: (c: unknown) => c,
  colunaDupla: (c: unknown) => c,
  colunaAcoes: () => ({}),
}))

import KnowledgeBasesPage from '../KnowledgeBasesPage'

const documento = (status: string) => ({
  id: 'doc-1', knowledgeBaseId: 'kb-1', fileId: 'file-1', status, chunkCount: status === 'READY' ? 1 : 0,
  error: null, createdAt: '2026-11-25T10:00:00Z', updatedAt: '2026-11-25T10:00:00Z',
})

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
  knowledgeApiMock.listDocuments.mockResolvedValue([documento('READY')])
  knowledgeApiMock.versions.mockResolvedValue([])
  knowledgeApiMock.getFile.mockResolvedValue({
    id: 'file-1', originalName: 'troca.txt', contentType: 'text/plain', size: 10, createdAt: '',
  })
  knowledgeApiMock.upload.mockResolvedValue({
    id: 'file-2', originalName: 'troca.txt', contentType: 'text/plain', size: 10, createdAt: '',
  })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function enviar() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(MemoryRouter, null, h(KnowledgeBasesPage))))
  })
  await waitFor(() => expect(knowledgeApiMock.listDocuments).toHaveBeenCalledTimes(1))
  await escolherArquivo()
}

async function escolherArquivo() {
  const input = await waitFor(() => {
    const found = document.querySelector('input[type="file"]')
    expect(found).not.toBeNull()
    return found as HTMLInputElement
  })
  await act(async () => {
    fireEvent.change(input, { target: { files: [new File(['Trocas'], 'troca.txt', { type: 'text/plain' })] } })
  })
}

describe('KnowledgeBasesPage — reenvio do mesmo conteúdo', () => {
  it('avisa que o conteúdo já estava na base e recarrega a lista', async () => {
    knowledgeApiMock.ingest.mockResolvedValue({
      document: documento('READY'),
      job: { id: 'job-1', status: 'SUCCEEDED', progress: 100, message: 'Completed', error: null },
      outcome: 'ALREADY_PRESENT',
    })

    await enviar()

    await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.objectContaining({
      title: 'knowledge.ingestionAlreadyPresent', message: 'troca.txt',
    })))
    expect(notify).not.toHaveBeenCalledWith(expect.objectContaining({ title: 'knowledge.ingestionQueued' }))
    await waitFor(() => expect(knowledgeApiMock.listDocuments).toHaveBeenCalledTimes(2))
  })

  it('avisa o reprocessamento do documento que falhou', async () => {
    knowledgeApiMock.listDocuments.mockResolvedValue([{ ...documento('FAILED'), error: 'falhou' }])
    knowledgeApiMock.ingest.mockResolvedValue({
      document: documento('QUEUED'),
      job: { id: 'job-1', status: 'QUEUED', progress: 0, message: 'Queued for retry', error: null },
      outcome: 'REPROCESSING',
    })

    await enviar()

    await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.objectContaining({
      title: 'knowledge.ingestionReprocessing', message: 'troca.txt',
    })))
  })

  it('conteúdo novo segue com o aviso de sempre, sem recarregar', async () => {
    knowledgeApiMock.ingest.mockResolvedValue({
      document: { ...documento('QUEUED'), id: 'doc-2', fileId: 'file-2' },
      job: { id: 'job-2', status: 'QUEUED', progress: 0, message: 'Queued', error: null },
      outcome: 'QUEUED',
    })

    await enviar()

    await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.objectContaining({
      title: 'knowledge.ingestionQueued', message: 'troca.txt',
    })))
    expect(knowledgeApiMock.listDocuments).toHaveBeenCalledTimes(1)
  })

  it('o fim do reprocessamento de um job que já tinha terminado na tela é avisado', async () => {
    // O job reaberto mantém o id; a tela já o tinha marcado como concluído na primeira falha.
    knowledgeApiMock.ingest.mockResolvedValueOnce({
      document: documento('QUEUED'),
      job: { id: 'job-1', status: 'QUEUED', progress: 0, message: 'Queued', error: null },
      outcome: 'QUEUED',
    })
    knowledgeApiMock.getJob.mockResolvedValueOnce(
      { id: 'job-1', status: 'FAILED', progress: 0, message: 'Failed', error: 'falhou' })
    await enviar()
    await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.objectContaining({
      title: 'knowledge.ingestionFailed',
    })), { timeout: 4000 })

    knowledgeApiMock.ingest.mockResolvedValueOnce({
      document: documento('QUEUED'),
      job: { id: 'job-1', status: 'QUEUED', progress: 0, message: 'Queued for retry', error: null },
      outcome: 'REPROCESSING',
    })
    knowledgeApiMock.getJob.mockResolvedValueOnce(
      { id: 'job-1', status: 'SUCCEEDED', progress: 100, message: 'Completed', error: null })
    await escolherArquivo()

    await waitFor(() => expect(notify).toHaveBeenCalledWith(expect.objectContaining({
      title: 'knowledge.ingestionComplete',
    })), { timeout: 4000 })
  })
})
