/**
 * RCHFLW-188, item 1: a notificação "Execução impedida" de um fluxo com várias referências quebradas tem mais de mil
 * caracteres. Com o padrão do Mantine (200px, overflow hidden, conteúdo centralizado) o título ficava acima da área
 * visível e a lista de correções terminava no meio de uma frase.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { Notifications, notifications } from '@mantine/notifications'
import { waitFor } from '@testing-library/dom'
import { LONG_NOTIFICATION_STYLES, NOTIFICATION_MAX_HEIGHT } from '../longNotification'

const LONGA = `${'«node-1795665204798» não é um passo do fluxo; '.repeat(24)}declare-a em «Campos de entrada esperados».`

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(async () => {
  await act(async () => { notifications.clean(); root?.unmount() })
  root = null
  container.remove()
})

describe('notificação longa', () => {
  it('a altura máxima não é a de 200px do Mantine, e a descrição rola em vez de ser cortada', async () => {
    expect(NOTIFICATION_MAX_HEIGHT).not.toBe(200)
    await act(async () => {
      root!.render(h(MantineProvider, null,
        h(Notifications, { notificationMaxHeight: NOTIFICATION_MAX_HEIGHT, transitionDuration: 0 })))
    })
    await act(async () => {
      notifications.show({ title: 'Execução impedida', message: LONGA, autoClose: false, styles: LONG_NOTIFICATION_STYLES })
    })

    await waitFor(() => expect(document.body.textContent).toContain('declare-a em «Campos de entrada esperados».'))
    const description = Array.from(document.body.querySelectorAll<HTMLElement>('.mantine-Notification-description'))
      .find(el => el.textContent === LONGA)
    expect(description).toBeDefined()
    expect(description!.style.maxHeight).toBe('45vh')
    expect(description!.style.overflowY).toBe('auto')
  })
})
