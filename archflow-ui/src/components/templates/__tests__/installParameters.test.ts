/**
 * RCHFLW-204 — o formulário do install só oferece os tipos que o JSON leva do jeito que o
 * WorkflowTemplate.validateParameters confere (type.isInstance). Um Long ou um Double que a tela aceitasse voltaria
 * 400: o Jackson lê um número pequeno como Integer.
 */
import { describe, it, expect } from 'vitest'
import { fieldKind, installFields } from '../installParameters'

describe('fieldKind', () => {
  it('Integer vira campo numérico; Long e Double não são oferecidos', () => {
    expect(fieldKind({ name: 'n', type: 'Integer', required: true })).toBe('integer')
    expect(fieldKind({ name: 'n', type: 'Long', required: true })).toBeNull()
    expect(fieldKind({ name: 'n', type: 'Double', required: true })).toBeNull()
  })

  it('a base de conhecimento usa o seletor; enum vira select', () => {
    expect(fieldKind({ name: 'knowledgeBaseId', type: 'String', required: true })).toBe('knowledgeBase')
    expect(fieldKind({ name: 'lang', type: 'String', required: true, options: ['en'] })).toBe('select')
  })

  it('só os obrigatórios com campo entram no formulário', () => {
    expect(installFields([
      { name: 'a', type: 'String', required: true },
      { name: 'b', type: 'Long', required: true },
      { name: 'c', type: 'Integer', required: false },
    ]).map((p) => p.name)).toEqual(['a'])
  })
})
