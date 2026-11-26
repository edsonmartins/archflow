/**
 * RCHFLW-188 (item 8): com a UI em inglês, um F5 deixava `<html lang>` em pt-BR. Os ouvintes que sincronizam o
 * lang eram registrados depois de um init() síncrono (recursos inline), e o evento do boot já tinha passado.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

beforeEach(() => {
  vi.resetModules()
  localStorage.clear()
  document.documentElement.lang = 'pt-BR'
})

describe('i18n — <html lang> no boot', () => {
  it('depois de recarregar com English escolhido, o lang é en', async () => {
    localStorage.setItem('archflow-language', 'en')

    const { default: i18n } = await import('../index')

    expect(i18n.language).toBe('en')
    expect(document.documentElement.lang).toBe('en')
  })

  it('e acompanha a troca de idioma', async () => {
    localStorage.setItem('archflow-language', 'en')
    const { default: i18n } = await import('../index')

    await i18n.changeLanguage('pt-BR')

    expect(document.documentElement.lang).toBe('pt-BR')
  })
})
