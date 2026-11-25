import { describe, it, expect } from 'vitest'
import { EXECUTION_STATUS_COLOR, executionPollIntervalMs, isTerminalExecutionStatus } from '../executionStatus'

describe('executionPollIntervalMs', () => {
  it('recarrega enquanto roda', () => {
    expect(executionPollIntervalMs('RUNNING')).toBe(2000)
  })

  it('recarrega também enquanto espera a aprovação — a decisão vem de outra tela', () => {
    expect(executionPollIntervalMs('PAUSED')).toBe(5000)
    expect(executionPollIntervalMs('AWAITING_APPROVAL')).toBe(5000)
  })

  it.each(['COMPLETED', 'FAILED', 'CANCELLED', 'STOPPED', '', null, undefined])(
    'não recarrega em %s', (status) => {
      expect(executionPollIntervalMs(status)).toBeNull()
    })
})

describe('STOPPED (recusada no portão de aprovação)', () => {
  it('é terminal e tem cor própria, distinta de falha e de cancelada', () => {
    expect(isTerminalExecutionStatus('STOPPED')).toBe(true)
    expect(EXECUTION_STATUS_COLOR.STOPPED).toBeDefined()
    expect(EXECUTION_STATUS_COLOR.STOPPED).not.toBe(EXECUTION_STATUS_COLOR.FAILED)
    expect(EXECUTION_STATUS_COLOR.STOPPED).not.toBe(EXECUTION_STATUS_COLOR.CANCELLED)
  })
})
