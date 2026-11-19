/**
 * Cores literais da tabela. A VTable desenha em canvas e não enxerga CSS — `var(--x)` não resolve —, então as
 * superfícies do App.css (`--surface-bg`, `--table-head-bg`, `--row-hover`, `--surface-border`…) são repetidas aqui
 * como valores. Ao mexer numa, mexa na outra.
 *
 * O tema vigente é lido da mesma fonte que o CSS usa (`html[data-mantine-color-scheme]`), para a tabela e a tela
 * nunca discordarem sobre qual está valendo.
 */
export const FONTE = "'Maple Mono', ui-monospace, 'SFMono-Regular', Menlo, monospace"

export const TOKENS = {
    light: {
        surface: '#FFFFFF',
        header: '#F8FAFD',
        hover: '#F3F7FF',
        selected: '#EAF1FF',
        border: '#E9EDF4',
        text: '#111827',
        muted: '#6B7280',
        dim: '#9CA3AF',
        accent: '#2563EB',
        barBg: '#EEF2F7',
    },
    dark: {
        surface: '#172033',
        header: '#1B2539',
        hover: '#1E2A42',
        selected: '#22314F',
        border: '#263247',
        text: '#F1F5F9',
        muted: '#94A3B8',
        dim: '#64748B',
        accent: '#93C5FD',
        barBg: '#263247',
    },
} as const

export type Tokens = Record<keyof (typeof TOKENS)['light'], string>

/** Verdadeiro quando o app está no tema escuro. Não é hook: roda na pintura do canvas. */
export function temaEscuro(): boolean {
    return typeof document !== 'undefined' && document.documentElement.dataset.mantineColorScheme === 'dark'
}

export function tokens(): Tokens {
    return temaEscuro() ? TOKENS.dark : TOKENS.light
}

/**
 * Tinta e fundo de selo por cor Mantine — a mesma semântica de cor que o `StatusBadge` usa (green = ok, red = falha,
 * orange/yellow = atenção, blue = em andamento, gray = neutro…), em literal para o canvas.
 */
const SELOS = {
    light: {
        green:  { tinta: '#15803D', fundo: '#DCFCE7' },
        teal:   { tinta: '#0F766E', fundo: '#CCFBF1' },
        red:    { tinta: '#B91C1C', fundo: '#FEE2E2' },
        orange: { tinta: '#C2410C', fundo: '#FFEDD5' },
        yellow: { tinta: '#A16207', fundo: '#FEF9C3' },
        blue:   { tinta: '#1D4ED8', fundo: '#DBEAFE' },
        cyan:   { tinta: '#0E7490', fundo: '#CFFAFE' },
        violet: { tinta: '#6D28D9', fundo: '#EDE9FE' },
        grape:  { tinta: '#7E22CE', fundo: '#F3E8FF' },
        pink:   { tinta: '#BE185D', fundo: '#FCE7F3' },
        gray:   { tinta: '#4B5563', fundo: '#F1F3F7' },
    },
    dark: {
        green:  { tinta: '#86EFAC', fundo: 'rgba(34,197,94,0.16)' },
        teal:   { tinta: '#5EEAD4', fundo: 'rgba(20,184,166,0.16)' },
        red:    { tinta: '#FCA5A5', fundo: 'rgba(239,68,68,0.16)' },
        orange: { tinta: '#FDBA74', fundo: 'rgba(249,115,22,0.16)' },
        yellow: { tinta: '#FDE047', fundo: 'rgba(234,179,8,0.16)' },
        blue:   { tinta: '#93C5FD', fundo: 'rgba(59,130,246,0.18)' },
        cyan:   { tinta: '#67E8F9', fundo: 'rgba(6,182,212,0.16)' },
        violet: { tinta: '#C4B5FD', fundo: 'rgba(139,92,246,0.18)' },
        grape:  { tinta: '#D8B4FE', fundo: 'rgba(168,85,247,0.18)' },
        pink:   { tinta: '#F9A8D4', fundo: 'rgba(236,72,153,0.16)' },
        gray:   { tinta: '#CBD5E1', fundo: 'rgba(148,163,184,0.14)' },
    },
} as const

export type CorDeSelo = keyof (typeof SELOS)['light']

export function selo(cor: string | undefined) {
    const paleta = temaEscuro() ? SELOS.dark : SELOS.light
    return paleta[(cor as CorDeSelo) in paleta ? (cor as CorDeSelo) : 'gray']
}
