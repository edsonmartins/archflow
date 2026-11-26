/**
 * Modelos (RCHFLW-195 itens 14–16): a busca acha o card pelo rótulo que ele mostra, a cópia ganha nome
 * em pt-BR e único, e os roteadores sem configuração são apontados antes de instalar.
 */
import { describe, it, expect } from 'vitest'
import i18next from 'i18next'
import ptBR from '../../../i18n/locales/pt-BR.json'
import { nomeDaCopia, roteadoresSemConfiguracao, templateCombinaComBusca } from '../categoria'
import { templateApi } from '../../../services/template-api'

const i18n = i18next.createInstance()
await i18n.init({ lng: 'pt-BR', resources: { 'pt-BR': { translation: ptBR } }, interpolation: { escapeValue: false } })
const t = i18n.t.bind(i18n)

const todos = templateApi.listSync()
const achados = (q: string) => todos.filter((tpl) => templateCombinaComBusca(tpl, q, t)).map((tpl) => tpl.id)

describe('busca de modelos', () => {
  it('acha pelo rótulo de categoria em pt-BR, com ou sem acento', () => {
    expect(achados('atendimento')).toContain('sac-basic')
    expect(achados('Código')).toContain('code-reviewer')
    expect(achados('codigo')).toContain('code-reviewer')
    expect(achados('pesquisa').length).toBeGreaterThan(0)
  })

  it('continua achando por nome e tag, e busca vazia acha todos', () => {
    expect(achados('support')).toContain('sac-basic')
    expect(achados('   ')).toHaveLength(todos.length)
  })
})

describe('nome da cópia', () => {
  it('em pt-BR e único entre os fluxos que já existem', () => {
    expect(nomeDaCopia('Customer support agent', [], t)).toBe('Customer support agent (cópia)')
    expect(nomeDaCopia('Customer support agent', ['customer support agent (cópia)'], t))
      .toBe('Customer support agent (cópia 2)')
    expect(nomeDaCopia('X', ['X (cópia)', 'X (cópia 2)'], t)).toBe('X (cópia 3)')
  })
})

describe('roteadores sem configuração', () => {
  it('aponta o switch e a condição dos modelos que chegam sem os campos que o motor exige', () => {
    const triagem = roteadoresSemConfiguracao(templateApi.get('email-triage')!.steps)
    expect(triagem).toEqual([{ id: 's3', label: 'Route', campos: [
      'editor.properties.fields.switchExpression', 'editor.properties.fields.switchCases'] }])
    const sac = roteadoresSemConfiguracao(templateApi.get('sac-basic')!.steps)
    expect(sac.map((r) => r.label)).toEqual(['Intent router'])
  })

  it('roteador configurado não é apontado', () => {
    expect(roteadoresSemConfiguracao([
      { id: 'c', type: 'condition', config: { conditionExpression: '${x} == 1' } },
      { id: 's', type: 'switch', config: { switchExpression: '${x}', switchCases: 'a\nb' } },
    ])).toEqual([])
  })
})
