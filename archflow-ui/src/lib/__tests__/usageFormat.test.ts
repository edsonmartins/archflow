/**
 * RCHFLW-105: um custo positivo nunca aparece como R$ 0,00. Com preço declarado, 14 + 50 tokens de um
 * modelo barato custam R$ 0,0000214 — quatro casas arredondavam para zero, e zero quer dizer "de graça".
 */
import { describe, it, expect } from 'vitest'
import { formatReais } from '../usageFormat'

/** O Intl separa símbolo e número com espaço inseparável; o teste compara o texto que a pessoa lê. */
const plano = (s: string) => s.replace(/\u00a0/g, ' ')

describe('formatReais', () => {
  it('custo positivo abaixo de R$ 0,0001 mostra "< R$ 0,0001"', () => {
    expect(plano(formatReais(0.0000214, 'pt-BR'))).toBe('< R$ 0,0001')
    expect(plano(formatReais(0.0000214, 'en'))).toBe('< R$0.0001')
  })

  it('zero de verdade continua R$ 0,00', () => {
    expect(plano(formatReais(0, 'pt-BR'))).toBe('R$ 0,00')
  })

  it('a partir de R$ 0,0001 mostra o valor, com até quatro casas', () => {
    expect(plano(formatReais(0.0001, 'pt-BR'))).toBe('R$ 0,0001')
    expect(plano(formatReais(0.0018, 'pt-BR'))).toBe('R$ 0,0018')
    expect(plano(formatReais(12.5, 'pt-BR'))).toBe('R$ 12,50')
  })
})
