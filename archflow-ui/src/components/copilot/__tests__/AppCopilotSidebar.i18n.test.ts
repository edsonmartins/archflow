/**
 * RCHFLW-82 — com a interface em português, o chat lateral do copilot aparecia em inglês ("Open chat",
 * "How can I help you today?", "Type a message...", "AI can make mistakes..."): o CopilotSidebar era
 * montado sem a prop {@code labels} e a biblioteca usava os padrões dela.
 *
 * <p>O CopilotSidebar é substituído por um dublê que guarda as props: o índice do v2 importa um .css que o
 * Node do vitest não carrega. O que importa aqui é o que o app entrega à biblioteca — todos os rótulos que
 * ela conhece ({@code CopilotChatDefaultLabels}, lido da própria biblioteca), traduzidos pelo i18n real
 * do app, nos dois idiomas.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { CopilotChatDefaultLabels } from '@copilotkit/react-core/v2/headless'

const sidebar = vi.hoisted(() => ({
  props: [] as Record<string, unknown>[],
  headerProps: [] as Record<string, unknown>[],
}))

vi.mock('@copilotkit/react-core/v2', () => ({
  CopilotSidebar: (props: Record<string, unknown>) => {
    sidebar.props.push(props)
    return null
  },
  // Dublê do cabeçalho da biblioteca: guarda os slots que o app entrega. Que o CloseButton/DrawerLauncher
  // reais espalham essas props depois do aria-label fixo ("Close", "Open threads") é da biblioteca
  // (CopilotModalHeader no 1.62.1) — aqui se prova o que o app passa.
  CopilotModalHeader: Object.assign((props: Record<string, unknown>) => {
    sidebar.headerProps.push(props)
    return null
  }, { Title: () => null, CloseButton: () => null, DrawerLauncher: () => null }),
}))

import i18n from '../../../i18n'
import AppCopilotSidebar from '../AppCopilotSidebar'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  // As ações que acionam o servidor só aparecem para quem tem a permissão (QA2 RCHFLW-136/158).
  entrarComo('ADMIN')
  sidebar.props = []
  sidebar.headerProps = []
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
  await i18n.changeLanguage('pt-BR')
})

async function labelsIn(language: string): Promise<Record<string, string>> {
  await i18n.changeLanguage(language)
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root!.render(h(AppCopilotSidebar)) })
  const last = sidebar.props.at(-1)
  expect(last).toBeDefined()
  return last!.labels as Record<string, string>
}

const ENGLISH_DEFAULTS = CopilotChatDefaultLabels as Record<string, string>

describe('AppCopilotSidebar — textos do chat vêm do i18n', () => {
  it('passa todos os rótulos que a biblioteca conhece, nenhum a menos', async () => {
    const labels = await labelsIn('pt-BR')

    expect(labels).toBeDefined()
    expect(Object.keys(labels).sort()).toEqual(Object.keys(ENGLISH_DEFAULTS).sort())
  })

  it('em português, nenhum rótulo fica com o padrão em inglês nem com a chave crua', async () => {
    const labels = await labelsIn('pt-BR')

    expect(labels.chatToggleOpenLabel).toBe('Abrir chat')
    expect(labels.welcomeMessageText).toBe('Como posso ajudar você hoje?')
    expect(labels.chatInputPlaceholder).toBe('Digite uma mensagem...')
    expect(labels.chatDisclaimerText).toBe('A IA pode errar. Confira as informações importantes.')
    for (const [key, value] of Object.entries(labels)) {
      expect(value, key).not.toBe(ENGLISH_DEFAULTS[key])
      expect(value, key).not.toMatch(/^copilot\.chat\./)
      expect(value.trim(), key).not.toBe('')
    }
  })

  it('em inglês, os textos vêm do en.json, sem chave crua', async () => {
    const labels = await labelsIn('en')

    expect(labels.chatToggleOpenLabel).toBe('Open chat')
    expect(labels.welcomeMessageText).toBe('How can I help you today?')
    for (const [key, value] of Object.entries(labels)) {
      expect(value, key).not.toMatch(/^copilot\.chat\./)
      expect(value.trim(), key).not.toBe('')
    }
  })

  it('trocar o idioma com o chat montado refaz os rótulos', async () => {
    await labelsIn('pt-BR')
    await act(async () => { await i18n.changeLanguage('en') })

    const last = sidebar.props.at(-1)!.labels as Record<string, string>
    expect(last.chatToggleOpenLabel).toBe('Open chat')
  })

  it('os botões do cabeçalho (fechar e abrir conversas) também vêm do i18n, nos dois idiomas', async () => {
    const header = await headerSlotsIn('pt-BR')
    expect(header.closeButton).toEqual({ 'aria-label': 'Fechar chat' })
    expect(header.drawerLauncher).toEqual({ 'aria-label': 'Abrir conversas' })

    await act(async () => { await i18n.changeLanguage('en') })
    const english = sidebar.headerProps.at(-1)!
    expect(english.closeButton).toEqual({ 'aria-label': 'Close chat' })
    expect(english.drawerLauncher).toEqual({ 'aria-label': 'Open threads' })
  })

  it('o cabeçalho tem identidade estável: trocar o idioma não remonta (nem fecha) a barra', async () => {
    await labelsIn('pt-BR')
    const before = sidebar.props.at(-1)!.header
    await act(async () => { await i18n.changeLanguage('en') })

    // O CopilotSidebar recria a vista da barra quando a prop header muda de identidade.
    expect(typeof before).toBe('function')
    expect(sidebar.props.at(-1)!.header).toBe(before)
  })
})

let headerRoot: Root | null = null

/** Monta a barra, depois o componente que ela recebeu em {@code header}, como a biblioteca faria. */
async function headerSlotsIn(language: string): Promise<Record<string, unknown>> {
  await labelsIn(language)
  const Header = sidebar.props.at(-1)!.header as (p: Record<string, unknown>) => null
  const el = document.createElement('div')
  container.appendChild(el)
  headerRoot = createRoot(el)
  await act(async () => { headerRoot!.render(h(Header, {})) })
  const last = sidebar.headerProps.at(-1)
  expect(last).toBeDefined()
  return last!
}

afterEach(async () => {
  await act(async () => { headerRoot?.unmount() })
  headerRoot = null
})
