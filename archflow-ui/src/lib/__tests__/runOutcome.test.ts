import { describe, it, expect } from 'vitest'
import { nodeStatusOfStepFinished, runOutcomeOf } from '../runOutcome'

describe('runOutcomeOf', () => {
  it.each([
    [{ type: 'RUN_FINISHED' }, 'completed'],
    [{ type: 'RUN_FINISHED', result: {} }, 'completed'],
    [{ type: 'RUN_FINISHED', result: { status: 'COMPLETED' } }, 'completed'],
    [{ type: 'RUN_FINISHED', result: { status: 'FAILED' } }, 'failed'],
    [{ type: 'RUN_FINISHED', result: { status: 'failed' } }, 'failed'],
    [{ type: 'RUN_FINISHED', result: { status: 'CANCELLED' } }, 'cancelled'],
    [{ type: 'RUN_FINISHED', result: { status: 'STOPPED' } }, 'cancelled'],
    [{ type: 'RUN_FINISHED', result: { status: 'PAUSED' } }, 'paused'],
    [{ type: 'RUN_FINISHED', result: { status: 'AWAITING_APPROVAL' } }, 'paused'],
    [{ type: 'RUN_FINISHED', result: { status: 'RUNNING' } }, 'unknown'],
    [{ type: 'RUN_FINISHED', result: null }, 'completed'],
    [{ type: 'RUN_ERROR', message: 'x' }, 'failed'],
    [{ type: 'STEP_FINISHED' }, 'unknown'],
  ])('%j → %s', (event, expected) => {
    expect(runOutcomeOf(event)).toBe(expected)
  })
})

describe('nodeStatusOfStepFinished', () => {
  // O portão de aprovação à espera fecha o passo com STEP_SUSPENDED: era pintado com ✓ (RCHFLW-32).
  it('STEP_SUSPENDED é aguardando, não sucesso', () => {
    expect(nodeStatusOfStepFinished('STEP_SUSPENDED')).toBe('awaiting')
  })

  it.each([
    ['STEP_COMPLETED', 'success'],
    ['STEP_FAILED', 'error'],
    ['STEP_SKIPPED', 'skipped'],
    // Servidor AG-UI genérico, sem status: conclusão, como antes
    [undefined, 'success'],
  ])('%s → %s', (status, expected) => {
    expect(nodeStatusOfStepFinished(status)).toBe(expected)
  })
})
