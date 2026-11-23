import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest'
import {
  loadLastRunInput, parseRunInput, saveLastRunInput, toAgUiRunInput, toExecuteBody,
} from '../runInput'

describe('runInput — texto do modal → corpo da requisição', () => {
  it('objeto JSON vira o mapa de variáveis do POST /execute', () => {
    expect(toExecuteBody('{"cliente": "ACME", "n": 2}')).toEqual({ cliente: 'ACME', n: 2 })
  })

  it('texto livre vira {input: texto}, sem trim', () => {
    expect(toExecuteBody('  olá  ')).toEqual({ input: '  olá  ' })
  })

  it('JSON que não é objeto (array, número, string, null) segue como texto', () => {
    expect(toExecuteBody('[1, 2]')).toEqual({ input: '[1, 2]' })
    expect(toExecuteBody('42')).toEqual({ input: '42' })
    expect(toExecuteBody('"x"')).toEqual({ input: '"x"' })
    expect(toExecuteBody('null')).toEqual({ input: 'null' })
  })

  it('JSON quebrado segue como texto', () => {
    expect(toExecuteBody('{"a": ')).toEqual({ input: '{"a": ' })
  })

  it('vazio (ou só espaços) não manda corpo — a execução sem entrada de antes', () => {
    expect(toExecuteBody('')).toBeUndefined()
    expect(toExecuteBody('   \n')).toBeUndefined()
    expect(parseRunInput('')).toEqual({ kind: 'empty' })
  })

  it('AG-UI: objeto → state, texto → última mensagem, vazio → {}', () => {
    expect(toAgUiRunInput('{"a": 1}')).toEqual({ state: { a: 1 } })
    expect(toAgUiRunInput('oi')).toEqual({ messages: [{ role: 'user', content: 'oi' }] })
    expect(toAgUiRunInput('')).toEqual({})
  })
})

describe('runInput — última entrada por fluxo', () => {
  beforeEach(() => sessionStorage.clear())
  afterEach(() => vi.restoreAllMocks())

  it('guarda por workflow e limpa quando vazio', () => {
    saveLastRunInput('wf-1', '{"a": 1}')
    saveLastRunInput('wf-2', 'texto')
    expect(loadLastRunInput('wf-1')).toBe('{"a": 1}')
    expect(loadLastRunInput('wf-2')).toBe('texto')
    expect(loadLastRunInput('wf-3')).toBe('')

    saveLastRunInput('wf-1', '')
    expect(loadLastRunInput('wf-1')).toBe('')
  })

  it('storage que lança não quebra: lê vazio e ignora a gravação', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('bloqueado') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('bloqueado') })
    expect(() => saveLastRunInput('wf-1', 'x')).not.toThrow()
    expect(loadLastRunInput('wf-1')).toBe('')
  })
})
