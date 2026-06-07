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

// Expand each supported code to also include its base language, e.g.
// 'pt-BR' → ['pt-BR', 'pt']. Required because with nonExplicitSupportedLngs
// i18next v26 reduces a code to its base for the supported-check
// (isSupportedCode('pt-BR') tests 'pt'); if the base isn't listed the resolve
// hierarchy is empty and every key renders raw. Deriving it here means adding
// a locale to SUPPORTED_LANGUAGES is a one-line change with no hidden coupling.
const SUPPORTED_LNGS = Array.from(
    new Set(
        SUPPORTED_LANGUAGES.flatMap(({ code }) =>
            code.includes('-') ? [code, code.split('-')[0]] : [code],
        ),
    ),
)

void i18n
    .use(LanguageDetector)
    .use(initReactI18next)
    .init({
        resources: {
            en:      { translation: en },
            'pt-BR': { translation: ptBR },
        },
        fallbackLng: 'pt-BR',
        supportedLngs: SUPPORTED_LNGS,
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
