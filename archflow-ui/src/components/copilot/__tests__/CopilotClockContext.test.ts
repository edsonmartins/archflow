/**
 * RCHFLW-98 — "que dia é hoje?" no copiloto respondia uma data inventada: nenhuma entrada de contexto levava a
 * data atual. A entrada vem do navegador (instante local com deslocamento, fuso IANA, dia da semana) e tem de
 * acompanhar o relógio enquanto a aba fica aberta.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'

const contexts = vi.hoisted(() => new Map<string, unknown>())

// Mesmo mock de CopilotAppOperator.configure.test.ts: guarda o último valor registrado por descrição.
vi.mock('@copilotkit/react-core/v2', () => ({
  useAgentContext: ({ description, value }: { description: string; value: unknown }) => {
    contexts.set(description, value)
  },
}))

import CopilotClockContext from '../CopilotClockContext'

type Clock = { now: string; timeZone: string; weekday: string }

const ORIGINAL_TZ = process.env.TZ
let root: Root | undefined
let container: HTMLElement | undefined

function clock(): Clock {
  const entries = [...contexts.entries()].filter(([d]) => d.includes('current local date and time'))
  expect(entries).toHaveLength(1)
  return entries[0][1] as Clock
}

function mount() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => root!.render(h(CopilotClockContext)))
}

beforeEach(() => {
  contexts.clear()
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  // Fuso fixo (com deslocamento negativo) para o teste não depender da máquina que o roda.
  process.env.TZ = 'America/Sao_Paulo'
  vi.useFakeTimers()
  // 25/11/2026 09:30:40 em São Paulo (12:30:40Z), uma quarta-feira.
  vi.setSystemTime(new Date('2026-11-25T12:30:40Z'))
})

afterEach(() => {
  act(() => root?.unmount())
  container?.remove()
  root = undefined
  container = undefined
  vi.useRealTimers()
  vi.restoreAllMocks()
  process.env.TZ = ORIGINAL_TZ
})

describe('CopilotClockContext', () => {
  it('registra o instante local com deslocamento, o fuso IANA e o dia da semana', () => {
    mount()
    expect(clock()).toEqual({ now: '2026-11-25T09:30-03:00', timeZone: 'America/Sao_Paulo', weekday: 'Wednesday' })
  })

  it('atualiza quando o minuto vira, e não antes', () => {
    mount()
    // Faltam 20 s para 09:31: um segundo antes da virada o valor ainda é o de 09:30.
    act(() => { vi.advanceTimersByTime(19_000) })
    expect(clock().now).toBe('2026-11-25T09:30-03:00')
    act(() => { vi.advanceTimersByTime(1_000) })
    expect(clock().now).toBe('2026-11-25T09:31-03:00')
    // Continua acompanhando depois da primeira virada (reagenda), inclusive a do dia.
    act(() => { vi.advanceTimersByTime(60_000) })
    expect(clock().now).toBe('2026-11-25T09:32-03:00')
    act(() => { vi.advanceTimersByTime((14 * 60 + 28) * 60_000) })
    expect(clock()).toEqual({ now: '2026-11-26T00:00-03:00', timeZone: 'America/Sao_Paulo', weekday: 'Thursday' })
  })

  it('limpa o timer ao desmontar', () => {
    mount()
    expect(vi.getTimerCount()).toBe(1)
    act(() => root!.unmount())
    root = undefined
    expect(vi.getTimerCount()).toBe(0)
  })

  // A máquina suspensa não avança o relógio dos timers: o relógio de parede pula e o disparo pendente não sai.
  // setSystemTime sem avançar os timers reproduz exatamente isso.
  function wakeAt(iso: string) {
    vi.setSystemTime(new Date(iso))
  }

  it('acerta o relógio quando a aba volta a ficar visível, sem esperar o timer pendente', () => {
    mount()
    wakeAt('2026-11-26T11:05:10Z')
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(clock().now).toBe('2026-11-25T09:30-03:00')
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(clock()).toEqual({ now: '2026-11-26T08:05-03:00', timeZone: 'America/Sao_Paulo', weekday: 'Thursday' })
    // Reagenda a partir do novo instante: faltam 50 s para 08:06, e continua havendo um único timer.
    expect(vi.getTimerCount()).toBe(1)
    act(() => { vi.advanceTimersByTime(50_000) })
    expect(clock().now).toBe('2026-11-26T08:06-03:00')
  })

  it('acerta o relógio quando a janela recebe foco', () => {
    mount()
    wakeAt('2026-11-26T11:05:10Z')
    act(() => { window.dispatchEvent(new Event('focus')) })
    expect(clock().now).toBe('2026-11-26T08:05-03:00')
    expect(vi.getTimerCount()).toBe(1)
  })

  it('não troca o valor registrado quando o foco chega no mesmo minuto', () => {
    mount()
    const before = clock()
    act(() => { vi.advanceTimersByTime(5_000) })
    act(() => { window.dispatchEvent(new Event('focus')) })
    expect(clock()).toBe(before)
  })

  it('remove os ouvintes de visibilidade e foco ao desmontar', () => {
    const docRemove = vi.spyOn(document, 'removeEventListener')
    const winRemove = vi.spyOn(window, 'removeEventListener')
    mount()
    act(() => root!.unmount())
    root = undefined
    expect(docRemove).toHaveBeenCalledWith('visibilitychange', expect.any(Function))
    expect(winRemove).toHaveBeenCalledWith('focus', expect.any(Function))
  })
})
