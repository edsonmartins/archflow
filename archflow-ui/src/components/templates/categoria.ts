import type { TFunction } from 'i18next'
import { TEMPLATE_CATEGORIES, type WorkflowTemplate } from '../../data/templates'
import type { Accent } from '../PageHeader'

/**
 * Categoria de template como as telas de templates a mostram. O catálogo do backend (`GET /api/templates`) usa
 * categorias próprias (`support`, `knowledge`, `processing`, `automation`) que o `TEMPLATE_CATEGORIES` do bundle não
 * conhece — ler `TEMPLATE_CATEGORIES[cat].color` direto derrubava a galeria inteira (tela em branco). Aqui toda
 * categoria tem rótulo, cor e accent, conhecida ou não.
 */

/** Sinônimos do backend para as categorias do bundle. */
const SINONIMOS: Record<string, string> = {
    support: 'customer_support',
    knowledge: 'rag',
}

const ACCENT: Record<string, Accent> = {
    customer_support: 'teal',
    rag: 'violet',
    content: 'rose',
    code: 'blue',
    research: 'amber',
    productivity: 'green',
}

/** A chave canônica da categoria (o sinônimo resolvido; vazio vira `other`). */
export function chaveDaCategoria(categoria: string | undefined | null): string {
    const c = (categoria ?? '').trim().toLowerCase()
    if (!c) return 'other'
    return SINONIMOS[c] ?? c
}

export interface CategoriaVisivel {
    chave: string
    rotulo: string
    cor: string
    accent: Accent
}

function humanizar(chave: string) {
    const s = chave.replace(/[_-]+/g, ' ')
    return s.charAt(0).toUpperCase() + s.slice(1)
}

export function categoriaVisivel(categoria: string | undefined | null, t: TFunction): CategoriaVisivel {
    const chave = chaveDaCategoria(categoria)
    const conhecida = (TEMPLATE_CATEGORIES as Record<string, { label: string; color: string } | undefined>)[chave]
    return {
        chave,
        rotulo: t(`templates.categories.${chave}`, { defaultValue: conhecida?.label ?? humanizar(chave) }),
        cor: conhecida?.color ?? 'gray',
        accent: ACCENT[chave] ?? 'slate',
    }
}

/** As categorias presentes no catálogo: as conhecidas na ordem do bundle, depois as outras em ordem alfabética. */
export function categoriasDoCatalogo(templates: WorkflowTemplate[]): string[] {
    const presentes = new Set(templates.map((tpl) => chaveDaCategoria(tpl.category)))
    const conhecidas = Object.keys(TEMPLATE_CATEGORIES).filter((c) => presentes.has(c))
    const outras = [...presentes].filter((c) => !conhecidas.includes(c)).sort()
    return [...conhecidas, ...outras]
}

export const COMPLEXITY_COLOR: Record<string, string> = {
    starter: 'teal',
    intermediate: 'yellow',
    advanced: 'red',
}

export function rotuloDaComplexidade(complexidade: string | undefined, t: TFunction): string {
    if (complexidade === 'starter') return t('templates.complexityStarter')
    if (complexidade === 'intermediate') return t('templates.complexityIntermediate')
    if (complexidade === 'advanced') return t('templates.complexityAdvanced')
    return complexidade ? humanizar(complexidade) : '—'
}
