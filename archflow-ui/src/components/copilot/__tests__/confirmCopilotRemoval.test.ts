/**
 * RCHFLW-78 — o removeNode do copiloto só tira um nó que já estava no fluxo com o OK do usuário. O modal é o
 * real (Mantine): confirmar resolve true, cancelar ou fechar resolve false — e o onClose que o ConfirmModal
 * dispara depois do onConfirm não pode virar a resposta.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { ModalsProvider } from '@mantine/modals'
import { waitFor } from '@testing-library/dom'
import { confirmCopilotNodeRemoval } from '../confirmCopilotRemoval'

const t = (key: string, opts?: Record<string, unknown>) => (opts ? `${key} ${JSON.stringify(opts)}` : key)

let root: Root
let container: HTMLElement

beforeEach(async () => {
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root.render(h(MantineProvider, null, h(ModalsProvider, null))) })
})

afterEach(async () => {
  await act(async () => { root.unmount() })
  container.remove()
})

async function ask() {
  let answer: Promise<boolean> | undefined
  await act(async () => { answer = confirmCopilotNodeRemoval(t, { id: 'node-7', label: 'Resposta' }) })
  const button = (text: string) => waitFor(() => {
    const found = [...document.querySelectorAll('button')].find(b => b.textContent === text)
    expect(found).toBeDefined()
    return found as HTMLButtonElement
  })
  return { answer: answer!, button }
}

describe('confirmCopilotNodeRemoval', () => {
  it('mostra o nó e resolve true quando o usuário confirma', async () => {
    const { answer, button } = await ask()
    expect(document.body.textContent).toContain('"label":"Resposta"')
    const confirm = await button('copilot.confirmRemoveNode.confirm')
    await act(async () => { confirm.click() })
    await expect(answer).resolves.toBe(true)
  })

  it('resolve false quando o usuário mantém o nó', async () => {
    const { answer, button } = await ask()
    const cancel = await button('copilot.confirmRemoveNode.cancel')
    await act(async () => { cancel.click() })
    await expect(answer).resolves.toBe(false)
  })
})
