/**
 * RCHFLW-61: com todas as subtarefas falhando antes da verificação, o Labs › Workflow Dinâmico dizia
 * "Os verificadores rejeitaram todos os resultados candidatos" — falso: nada tinha sido verificado. A tela
 * agora diz quantas falharam e por quê, e a explicação de "nada confirmado" segue o que houve.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const apiMock = vi.hoisted(() => ({ run: vi.fn() }))

// Interpolação visível: a chave e os parâmetros, para conferir as contagens sem depender do i18n.
vi.mock('react-i18next', () => ({
    useTranslation: () => ({
        t: (k: string, o?: Record<string, unknown>) => (o ? `${k} ${JSON.stringify(o)}` : k),
        i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
    }),
}))
// A tabela de verdade desenha em canvas; esta escreve cada linha com o fieldFormat das colunas.
vi.mock('../../components/VTable', async () => {
    const { createElement } = await import('react')
    type Col = { field: string; fieldFormat?: (r: Record<string, unknown>) => string }
    return {
        VxTable: ({ records, columns, testid }: {
            records: Record<string, unknown>[]; columns: Col[]; testid: string
        }) => createElement('div', { 'data-testid': testid },
            records.map((r, i) => createElement('div', { key: i, 'data-testid': `${testid}-row` },
                columns.map(c => (c.fieldFormat ? c.fieldFormat(r) : String(r[c.field] ?? ''))).join(' | ')))),
        colunaApagada: (c: unknown) => c,
        colunaStatus: (c: unknown) => c,
    }
})
vi.mock('../../services/orchestration-api', async importOriginal => {
    const real = await importOriginal<typeof import('../../services/orchestration-api')>()
    return { ...real, orchestrationApi: apiMock }
})

import DynamicWorkflowPage from '../DynamicWorkflowPage'

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
    // O Textarea autosize do Mantine escuta `document.fonts`, que o jsdom não tem.
    if (!(document as any).fonts) {
        Object.defineProperty(document, 'fonts', {
            value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
        })
    }
})

afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
})

async function runWith(response: unknown, budget?: string) {
    apiMock.run.mockResolvedValue(response)
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
        root!.render(h(MantineProvider, { env: 'test' }, h(DynamicWorkflowPage)))
    })
    const ui = within(container)
    await act(async () => {
        fireEvent.change(ui.getByTestId('dw-goal'), { target: { value: 'Comparar três fornecedores de frete' } })
        if (budget != null) {
            fireEvent.change(ui.getByLabelText('dynamicWorkflow.budgetTokens'), { target: { value: budget } })
        }
    })
    await act(async () => { fireEvent.click(ui.getByTestId('dw-run')) })
    await waitFor(() => ui.getByTestId('dw-result'))
    return ui
}

const UNSUPPORTED = 'research-agent: Unsupported task type: orchestration.subtask'
const NO_MATCH = 'no agent matched subtask: zzz'

describe('DynamicWorkflowPage — subtarefas que falharam', () => {
    it('todas falharam: diz quantas e por quê, e não fala em verificadores', async () => {
        const ui = await runWith({
            confirmed: [], confirmedCount: 0, rounds: 1, subtasks: 3, failedCount: 3,
            failures: [
                { round: 1, subtask: 'a', error: UNSUPPORTED },
                { round: 1, subtask: 'b', error: UNSUPPORTED },
                { round: 1, subtask: 'zzz', error: NO_MATCH },
            ],
            trace: [
                { type: 'planned', round: 1, detail: '3 subtask(s)', confirmed: null },
                { type: 'failed', round: 1, detail: `a — ${UNSUPPORTED}`, confirmed: null },
                { type: 'round', round: 1, detail: 'producedNew=false, confirmed=0', confirmed: null },
            ],
        })

        const falhas = ui.getByTestId('dw-failures')
        expect(falhas.textContent).toContain('dynamicWorkflow.failuresTitle {"failed":3,"total":3}')
        // Agrupado por motivo: o mesmo motivo duas vezes vira uma linha com a contagem.
        const itens = within(falhas).getAllByRole('listitem').map(li => li.textContent)
        expect(itens).toEqual([`2× ${UNSUPPORTED}`, `1× ${NO_MATCH}`])

        expect(ui.getByText('dynamicWorkflow.noConfirmedAllFailedHint')).toBeTruthy()
        expect(ui.queryByText('dynamicWorkflow.noConfirmedHint')).toBeNull()

        // A linha "failed" do trace sai como falha, não com o veredito vazio.
        const linhas = ui.getAllByTestId('dw-trace-row').map(r => r.textContent)
        expect(linhas.find(l => l?.includes(UNSUPPORTED))).toContain('dynamicWorkflow.verdict.failed')
    })

    it('algumas falharam e as outras foram rejeitadas: mostra as falhas e mantém o texto dos verificadores', async () => {
        const ui = await runWith({
            confirmed: [], confirmedCount: 0, rounds: 1, subtasks: 2, failedCount: 1,
            failures: [{ round: 1, subtask: 'zzz', error: NO_MATCH }],
            trace: [{ type: 'verified', round: 1, detail: 'x', confirmed: false }],
        })

        expect(ui.getByTestId('dw-failures').textContent)
            .toContain('dynamicWorkflow.failuresTitle {"failed":1,"total":2}')
        expect(ui.getByText('dynamicWorkflow.noConfirmedHint')).toBeTruthy()
    })

    it('nenhuma falha: não há aviso de falhas', async () => {
        const ui = await runWith({
            confirmed: ['ok'], confirmedCount: 1, rounds: 1, subtasks: 1, failedCount: 0, failures: [],
            trace: [{ type: 'verified', round: 1, detail: 'ok', confirmed: true }],
        })

        expect(ui.queryByTestId('dw-failures')).toBeNull()
        expect(ui.queryByText('dynamicWorkflow.noConfirmedHint')).toBeNull()
    })

    it('o planejador não gerou subtarefas: diz isso, não que os verificadores rejeitaram', async () => {
        const ui = await runWith({
            confirmed: [], confirmedCount: 0, rounds: 1, subtasks: 0, failedCount: 0, failures: [],
            trace: [{ type: 'round', round: 1, detail: 'producedNew=false, confirmed=0', confirmed: null }],
        })

        expect(ui.getByText('dynamicWorkflow.noConfirmedNoSubtasksHint')).toBeTruthy()
        expect(ui.queryByText('dynamicWorkflow.noConfirmedHint')).toBeNull()
        expect(ui.queryByTestId('dw-failures')).toBeNull()
    })
})

/**
 * RCHFLW-62: o orçamento não tinha efeito e a tela não dizia nada sobre gasto. RCHFLW-64: o trace não dizia
 * qual agente atendeu cada subtarefa.
 */
describe('DynamicWorkflowPage — tokens, orçamento e agente', () => {
    const base = {
        confirmed: ['ok'], confirmedCount: 1, rounds: 1, subtasks: 2, failedCount: 1,
        failures: [{ round: 1, subtask: 'zzz', error: NO_MATCH }],
    }

    it('a coluna Agente mostra o componente de cada subtarefa', async () => {
        const ui = await runWith({
            ...base, tokensSpent: 15, usageUnknown: 0, budgetExhausted: false,
            trace: [
                { type: 'dispatched', round: 1, detail: 'research (score=0.42, success=true)', confirmed: null,
                  componentId: 'research-agent' },
                { type: 'dispatched', round: 1, detail: 'zzz (no agent matched)', confirmed: null,
                  componentId: null },
                { type: 'round', round: 1, detail: 'producedNew=true, confirmed=1', confirmed: null },
            ],
        })

        const linhas = ui.getAllByTestId('dw-trace-row').map(r => r.textContent ?? '')
        expect(linhas[0]).toContain('| research-agent |')
        // Sem agente casado (e linhas que não são de subtarefa): a célula fica vazia, não "null".
        expect(linhas[1]).not.toContain('null')
        expect(linhas[2]).not.toContain('null')
    })

    it('orçamento esgotado: mostra os tokens gastos e o aviso com o teto da execução', async () => {
        const ui = await runWith({
            ...base, tokensSpent: 160, usageUnknown: 0, budgetExhausted: true,
            trace: [{ type: 'budget-exhausted', round: 1, detail: 'spent=160 tokens, limit=150', confirmed: null }],
        }, '150')

        expect(ui.getByText('160')).toBeTruthy()
        expect(ui.getByText('dynamicWorkflow.stats.tokensBudgetHint {"budget":150}')).toBeTruthy()
        expect(ui.getByTestId('dw-budget-exhausted').textContent)
            .toContain('dynamicWorkflow.budgetExhaustedHint {"budget":150}')
    })

    /**
     * O planejador gastou o orçamento: planejou 2 subtarefas e nenhuma saiu (subtasks = 0). A tela dizia
     * "o planejador não gerou nenhuma subtarefa" ao lado do aviso de orçamento — falso.
     */
    it('orçamento gasto no planejamento: diz que as planejadas não saíram, não que o planejador não gerou', async () => {
        const ui = await runWith({
            confirmed: [], confirmedCount: 0, rounds: 1, subtasks: 0, failedCount: 0, failures: [],
            tokensSpent: 15, usageUnknown: 0, budgetExhausted: true, notDispatched: 2,
            trace: [
                { type: 'planned', round: 1, detail: '2 subtask(s): [a, b]', confirmed: null },
                { type: 'budget-exhausted', round: 1, detail: 'spent=15 tokens, limit=10', confirmed: null },
                { type: 'not-dispatched', round: 1, detail: 'a — budget exhausted', confirmed: null },
                { type: 'not-dispatched', round: 1, detail: 'b — budget exhausted', confirmed: null },
            ],
        }, '10')

        expect(ui.getByText('dynamicWorkflow.noConfirmedBudgetHint {"count":2}')).toBeTruthy()
        expect(ui.queryByText('dynamicWorkflow.noConfirmedNoSubtasksHint')).toBeNull()
        expect(ui.getByTestId('dw-budget-exhausted')).toBeTruthy()
    })

    it('teto 0: o planejador nem rodou, e a tela diz isso', async () => {
        const ui = await runWith({
            confirmed: [], confirmedCount: 0, rounds: 0, subtasks: 0, failedCount: 0, failures: [],
            tokensSpent: 0, usageUnknown: 0, budgetExhausted: true, notDispatched: 0,
            trace: [{ type: 'budget-exhausted', round: 0, detail: 'spent=0 tokens, limit=0', confirmed: null }],
        }, '0')

        expect(ui.getByText('dynamicWorkflow.noConfirmedBudgetBeforePlanningHint')).toBeTruthy()
        expect(ui.queryByText('dynamicWorkflow.noConfirmedNoSubtasksHint')).toBeNull()
    })

    it('uso não informado: o total aparece como piso, com quantas chamadas faltaram', async () => {
        const ui = await runWith({ ...base, tokensSpent: 30, usageUnknown: 2, budgetExhausted: false, trace: [] })

        expect(ui.getByText('≥ 30')).toBeTruthy()
        expect(ui.getByText('dynamicWorkflow.stats.tokensUnknownHint {"count":2}')).toBeTruthy()
        expect(ui.queryByTestId('dw-budget-exhausted')).toBeNull()
    })
})
