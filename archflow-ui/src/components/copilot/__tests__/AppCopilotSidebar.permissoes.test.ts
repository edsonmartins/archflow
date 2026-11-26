/**
 * QA2 RCHFLW-158: cada mensagem do copilot lateral é um `POST /ag-ui/agent`, que o servidor recusa (403)
 * sem `workflow:execute`. Um VIEWER via o chat, digitava e recebia erro a cada envio; agora a barra nem
 * monta para quem não pode usá-la.
 *
 * <p>O CopilotSidebar é um dublê (o índice do v2 importa um .css que o Node do vitest não carrega): o que
 * se prova é se o app o monta.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'

const montagens = vi.hoisted(() => ({ total: 0 }))

vi.mock('@copilotkit/react-core/v2', () => ({
  CopilotSidebar: () => {
    montagens.total += 1
    return null
  },
  CopilotModalHeader: Object.assign(() => null, { Title: () => null, CloseButton: () => null, DrawerLauncher: () => null }),
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))

import AppCopilotSidebar from '../AppCopilotSidebar'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  montagens.total = 0
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function montada(): Promise<boolean> {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root!.render(h(AppCopilotSidebar)) })
  return montagens.total > 0
}

describe('AppCopilotSidebar — só para quem tem workflow:execute (QA2 RCHFLW-158)', () => {
  it('VIEWER: a barra do copilot não monta', async () => {
    entrarComo('VIEWER')
    expect(await montada()).toBe(false)
  })

  for (const papel of ['EXECUTOR', 'DESIGNER', 'ADMIN']) {
    it(`${papel}: a barra monta`, async () => {
      entrarComo(papel)
      expect(await montada()).toBe(true)
    })
  }
})
