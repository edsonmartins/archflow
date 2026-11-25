/**
 * RCHFLW-66: a página Skills prometia um efeito que não existe ("Desligue uma para escondê-la dos
 * agentes", "Disponíveis aos agentes") — nenhum agente nem nó de fluxo lê skills hoje. O switch grava a
 * escolha por tenant, e é isso que o texto passa a dizer.
 */
import { describe, it, expect } from 'vitest'
import ptBR from '../../i18n/locales/pt-BR.json'
import en from '../../i18n/locales/en.json'

describe('Skills — o texto diz o que o switch faz', () => {
    it('pt-BR não promete efeito nos agentes', () => {
        const s = (ptBR as any).skills
        expect(s.subtitle).not.toMatch(/escondê-la dos agentes/)
        expect(s.stats.activeHint).not.toMatch(/agentes/)
        expect(s.subtitle).toMatch(/tenant/)
        expect(s.subtitle).toMatch(/nenhum agente/)
    })

    it('en não promete efeito nos agentes', () => {
        const s = (en as any).skills
        expect(s.subtitle).not.toMatch(/hide it from agents/)
        expect(s.stats.activeHint).not.toMatch(/agents/)
        expect(s.subtitle).toMatch(/tenant/)
        expect(s.subtitle).toMatch(/no agent/)
    })

    // O perfil dev guarda o estado em memória (InMemorySkillActivationStore): um "o estado fica salvo"
    // sem condição fazia a checagem de restart no navegador parecer que o fix falhou. Só o store JDBC
    // (archflow.persistence.jdbc.enabled=true) sobrevive a restart, e o texto precisa dizer isso.
    it('pt-BR diz que só sobrevive a restart com persistência JDBC', () => {
        const hint = (ptBR as any).skills.listHint as string
        expect(hint).not.toMatch(/o estado fica salvo$/)
        expect(hint).toMatch(/archflow\.persistence\.jdbc\.enabled=true/)
        expect(hint).toMatch(/perfil dev/)
    })

    it('en diz que só sobrevive a restart com persistência JDBC', () => {
        const hint = (en as any).skills.listHint as string
        expect(hint).not.toMatch(/the state is saved$/)
        expect(hint).toMatch(/archflow\.persistence\.jdbc\.enabled=true/)
        expect(hint).toMatch(/dev profile/)
    })
})
