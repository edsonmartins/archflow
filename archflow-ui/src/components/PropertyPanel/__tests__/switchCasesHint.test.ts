/**
 * A dica do campo "Cases" do switch (RCHFLW-135).
 *
 * A dica antiga dizia que a palavra `default` nos cases "casa com qualquer coisa". Não casa: o default não é
 * um caso, é o ramo de uma ARESTA. Depois do falhar fechado, o valor que não casa com nenhum caso, num switch
 * sem aresta default, falha o passo — quem seguia a dica listava `default`, ligava só os casos e via a execução
 * falhar. A dica tem de dizer o que o motor faz, e o seletor da aresta já oferece `default` sem ele estar listado.
 */
import { describe, it, expect } from 'vitest'
import { branchOptions } from '../../FlowCanvas/branches'
import ptBR from '../../../i18n/locales/pt-BR.json'
import en from '../../../i18n/locales/en.json'

type Locale = { editor: { properties: { fields: Record<string, string> } } }

function dica(locale: unknown): string {
  return (locale as Locale).editor.properties.fields.switchCasesHint
}

describe('switchCasesHint — o default é uma aresta, não um caso', () => {
  it('pt-BR manda ligar a aresta default e avisa que, sem ela, o passo falha', () => {
    expect(dica(ptBR)).not.toContain('corresponde a qualquer coisa')
    expect(dica(ptBR)).toContain('ligue uma aresta com branch default, ou o passo falha')
  })

  it('en manda ligar a aresta default e avisa que, sem ela, o passo falha', () => {
    expect(dica(en)).not.toContain('matches anything unmatched')
    expect(dica(en)).toContain('connect an edge with branch default, or the step fails')
  })

  it('o seletor da aresta oferece default sem ele estar entre os cases', () => {
    expect(branchOptions('switch', { switchCases: 'A\nB' })).toEqual(['A', 'B', 'default'])
  })
})
