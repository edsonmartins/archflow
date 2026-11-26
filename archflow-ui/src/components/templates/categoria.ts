import type { TFunction } from 'i18next'
import { TEMPLATE_CATEGORIES, type WorkflowTemplate } from '../../data/templates'
import type { Accent } from '../PageHeader'
import { REQUIRED_NODE_FIELDS } from '../FlowCanvas/runReadiness'

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

/** Minúsculas e sem acento: "Código" e "codigo" são a mesma busca. */
function semAcento(texto: string): string {
    return texto.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
}

/**
 * O card corresponde à busca? Além de nome, resumo e tags, compara os rótulos que o card mostra — a
 * categoria e a complexidade, no idioma da tela. Antes "atendimento", "pesquisa" ou "Código" davam "nenhum
 * modelo corresponde" para cards que exibiam exatamente esse rótulo (RCHFLW-195 item 14).
 */
export function templateCombinaComBusca(tpl: WorkflowTemplate, busca: string, t: TFunction): boolean {
    const q = semAcento(busca.trim())
    if (!q) return true
    const textos = [
        tpl.name ?? '',
        tpl.summary ?? '',
        ...(tpl.tags ?? []),
        categoriaVisivel(tpl.category, t).rotulo,
        rotuloDaComplexidade(tpl.complexity, t),
    ]
    return textos.some((texto) => semAcento(texto).includes(q))
}

/**
 * O nome do fluxo criado a partir de um modelo: "<nome> (cópia)" no idioma da tela e, se já existir um
 * fluxo com esse nome, "(cópia 2)", "(cópia 3)"… Antes era sempre "<nome> (copy)", em inglês, e quatro
 * instalações davam quatro fluxos com o mesmo nome, sem aviso (RCHFLW-195 item 15).
 */
export function nomeDaCopia(base: string, existentes: string[], t: TFunction): string {
    const usados = new Set(existentes.map((n) => n.trim().toLocaleLowerCase()))
    const primeiro = t('templates.detail.copyName', { name: base })
    if (!usados.has(primeiro.toLocaleLowerCase())) return primeiro
    for (let n = 2; ; n++) {
        const candidato = t('templates.detail.copyNameN', { name: base, n })
        if (!usados.has(candidato.toLocaleLowerCase())) return candidato
    }
}

/** Um roteador (condição/switch) do modelo e os campos que o motor exige e ele não traz. */
export interface RoteadorSemConfiguracao {
    id: string
    label: string
    /** Chaves i18n dos rótulos dos campos que faltam (as mesmas do painel de propriedades). */
    campos: string[]
}

/**
 * Os roteadores do modelo que chegam sem a configuração que o motor exige. O "Intent router" do SAC vem com
 * `{strategy:'llm'}` e o switch do email-triage com `branches` — nenhum dos dois é lido pelo ControlStep
 * (`conditionExpression`, `switchExpression`/`switchCases`), e quem instalava só descobria no Executar
 * (RCHFLW-195 item 16). O detalhe avisa antes de instalar; preencher a expressão é trabalho de quem usa o
 * modelo — inventar uma aqui decidiria o roteamento por ele.
 */
export function roteadoresSemConfiguracao(
    steps: { id: string; type?: string; componentId?: string; label?: string; config?: Record<string, unknown> }[],
): RoteadorSemConfiguracao[] {
    const vazio = (v: unknown) => v == null || (typeof v === 'string' && v.trim() === '')
        || (Array.isArray(v) && v.length === 0)
    return steps.flatMap((step) => {
        const tipo = (step.type || step.componentId || '').toLowerCase()
        if (tipo !== 'condition' && tipo !== 'switch') return []
        const campos = (REQUIRED_NODE_FIELDS[tipo] ?? [])
            .filter((f) => vazio(step.config?.[f.key]))
            .map((f) => f.labelKey)
        return campos.length ? [{ id: step.id, label: step.label || step.id, campos }] : []
    })
}
