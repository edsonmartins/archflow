import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import LanguageDetector from 'i18next-browser-languagedetector'

import en from './locales/en.json'
import ptBR from './locales/pt-BR.json'

export const SUPPORTED_LANGUAGES = [
    { code: 'pt-BR', label: 'Português (BR)', flag: 'BR' },
    { code: 'en',    label: 'English',        flag: 'US' },
] as const

export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number]['code']

void i18n
    .use(LanguageDetector)
    .use(initReactI18next)
    .init({
        resources: {
            en:      { translation: en },
            'pt-BR': { translation: ptBR },
        },
        fallbackLng: 'pt-BR',
        supportedLngs: ['pt-BR', 'en'],
        nonExplicitSupportedLngs: true,
        interpolation: { escapeValue: false },
        detection: {
            order: ['localStorage', 'navigator'],
            lookupLocalStorage: 'archflow-language',
            caches: ['localStorage'],
        },
        returnNull: false,
    })

// Keep <html lang> in sync so screen readers and spellcheckers pick up
// the active locale. Fires on boot (via 'initialized') and on change.
const syncHtmlLang = (lng: string) => {
    if (typeof document !== 'undefined') {
        document.documentElement.lang = lng
    }
}
i18n.on('initialized', () => syncHtmlLang(i18n.language))
i18n.on('languageChanged', syncHtmlLang)

export default i18n
