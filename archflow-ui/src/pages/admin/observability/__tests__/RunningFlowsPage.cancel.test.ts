/**
 * Running (Admin › Observabilidade), RCHFLW-72: confirmar "Cancelar fluxo" só tira a linha quando o
 * servidor aceitou. Antes o erro era engolido e a linha saía do mesmo jeito — o operador achava que
 * tinha parado uma execução que seguia rodando.
 *
 * <p>A tabela desenha em canvas; aqui ela vira uma lista de botões que disparam o mesmo
 * {@code onIconClick('cancel', linha)} do ícone vermelho.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const apiMock = vi.hoisted(() => ({ listRunningFlows: vi.fn(), cancelRunningFlow: vi.fn() }))
const notificationsMock = vi.hoisted(() => ({ show: vi.fn() }))

vi.mock('react-i18next', () => ({
    useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
vi.mock('../../../../services/observability-api', () => ({ observabilityApi: apiMock }))
vi.mock('../../../../components/VTable', async () => {
    const { createElement } = await import('react')
    type Linha = Record<string, unknown>
    return {
        VxTable: (props: { records: Linha[]; onIconClick: (nome: string, r: Linha) => void;
            rowTestId: (r: Linha) => string }) =>
            createElement('div', null, props.records.map((r) => createElement('button', {
                key: String(r.flowId),
                'data-testid': props.rowTestId(r),
                onClick: () => props.onIconClick('cancel', r),
            }, String(r.flowId)))),
        colunaAcoes: () => ({ field: 'acoes', title: '' }),
        colunaApagada: (c: unknown) => c,
        colunaBarra: (c: unknown) => c,
        colunaStatus: (c: unknown) => c,
    }
})

import RunningFlowsPage from '../RunningFlowsPage'
import { ApiError } from '../../../../services/api'

const linha = {
    flowId: 'exec-1', tenantId: 'tenant-a', startedAt: new Date().toISOString(),
    currentStepId: 'lento', stepIndex: 0, stepCount: 2, durationMs: 1000,
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    window.matchMedia ??= ((q: string) => ({
        matches: false, media: q, onchange: null,
        addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
        dispatchEvent: () => false,
    })) as any
    ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
    vi.clearAllMocks()
    apiMock.listRunningFlows.mockResolvedValue([linha])
})

afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
})

async function confirmarCancelamento() {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => { root!.render(h(MantineProvider, null, h(RunningFlowsPage))) })
    const ui = within(container)
    const botao = await waitFor(() => ui.getByTestId('running-row-exec-1'))
    await act(async () => { fireEvent.click(botao) })
    const modal = within(document.body)
    const confirmar = await waitFor(() => modal.getByText('admin.observability.running.cancelBtn'))
    await act(async () => { fireEvent.click(confirmar) })
    return ui
}

describe('RunningFlowsPage — cancelar', () => {
    it('servidor aceitou → chama o cancel e tira a linha', async () => {
        apiMock.cancelRunningFlow.mockResolvedValue(undefined)

        const ui = await confirmarCancelamento()

        expect(apiMock.cancelRunningFlow).toHaveBeenCalledWith('exec-1')
        await waitFor(() => expect(ui.queryByTestId('running-row-exec-1')).toBeNull())
        expect(notificationsMock.show).not.toHaveBeenCalled()
    })

    // O motor descarta o passo em curso mas a entrada só sai quando ele acaba: sem a marca, a linha
    // voltava no poll seguinte e parecia um cancelar que falhou.
    it('cancelado, mas o passo em curso ainda não acabou → o poll seguinte não traz a linha de volta', async () => {
        apiMock.cancelRunningFlow.mockResolvedValue(undefined)
        const ui = await confirmarCancelamento()
        await waitFor(() => expect(ui.queryByTestId('running-row-exec-1')).toBeNull())

        const chamadas = apiMock.listRunningFlows.mock.calls.length
        await act(async () => { fireEvent.click(ui.getByLabelText('admin.observability.running.refreshNow')) })
        await waitFor(() => expect(apiMock.listRunningFlows.mock.calls.length).toBeGreaterThan(chamadas))

        expect(ui.queryByTestId('running-row-exec-1')).toBeNull()
    })

    it('servidor recusou (404) → mostra o erro no idioma da tela e a linha fica', async () => {
        apiMock.cancelRunningFlow.mockRejectedValue(new ApiError(404, 'Flow not running: exec-1'))

        const ui = await confirmarCancelamento()

        await waitFor(() => expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
            color: 'red',
            title: 'admin.observability.running.cancelFailed',
            message: 'admin.observability.running.cancelNotRunning',
        })))
        expect(ui.getByTestId('running-row-exec-1')).toBeTruthy()
    })

    it('outra recusa → a frase do servidor, e a linha fica', async () => {
        apiMock.cancelRunningFlow.mockRejectedValue(new ApiError(500, 'Cancelamento indisponível'))

        const ui = await confirmarCancelamento()

        await waitFor(() => expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
            color: 'red',
            message: 'Cancelamento indisponível',
        })))
        expect(ui.getByTestId('running-row-exec-1')).toBeTruthy()
    })
})
