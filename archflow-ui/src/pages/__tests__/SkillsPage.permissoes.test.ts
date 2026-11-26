/**
 * QA2 RCHFLW-158: ativar/desativar uma skill muda o que os agentes do tenant fazem — o servidor exige
 * `agent:manage` (nenhum papel built-in além do ADMIN a declara). Quem não a tem vê o estado, mas o
 * switch fica desabilitado com o aviso, em vez de virar 403 a cada clique.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor } from '@testing-library/dom'

const skillsMock = vi.hoisted(() => ({
  list: vi.fn(), active: vi.fn(), activate: vi.fn(), deactivate: vi.fn(), resource: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../services/skills-api', () => ({ skillsApi: skillsMock }))

import SkillsPage from '../SkillsPage'

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
  skillsMock.list.mockResolvedValue([{ name: 'resumo', description: 'Resume textos', resources: [], active: false }])
  skillsMock.activate.mockResolvedValue({ name: 'resumo', description: '', resources: [], active: true })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(): Promise<HTMLInputElement> {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root!.render(h(MantineProvider, null, h(SkillsPage))) })
  return waitFor(() => {
    const el = container.querySelector('[data-testid="skill-toggle-resumo"]')
    expect(el).not.toBeNull()
    return el as HTMLInputElement
  })
}

describe('SkillsPage — ativar exige agent:manage (QA2 RCHFLW-158)', () => {
  for (const papel of ['VIEWER', 'EXECUTOR', 'DESIGNER']) {
    it(`${papel}: o switch fica desabilitado, dizendo que falta agent:manage`, async () => {
      entrarComo(papel)
      const toggle = await render()

      expect(toggle.disabled).toBe(true)
      expect(toggle.closest('[data-permission-missing]')?.getAttribute('data-permission-missing'))
        .toBe('agent:manage')
      await act(async () => { fireEvent.click(toggle) })
      expect(skillsMock.activate).not.toHaveBeenCalled()
    })
  }

  it('ADMIN: o switch ativa a skill', async () => {
    entrarComo('ADMIN')
    const toggle = await render()

    expect(toggle.disabled).toBe(false)
    await act(async () => { fireEvent.click(toggle) })
    await waitFor(() => expect(skillsMock.activate).toHaveBeenCalledWith('resumo'))
  })
})
