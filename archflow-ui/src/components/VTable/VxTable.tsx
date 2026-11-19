import { useEffect, useMemo, useRef, type ReactNode } from 'react'
import { Alert, Button, Group, Skeleton, Stack, Text, useComputedColorScheme, VisuallyHidden } from '@mantine/core'
import { IconAlertCircle, IconInbox } from '@tabler/icons-react'
import { ListTable, registerProgressBarCell } from '@visactor/vtable'
import type { ListTableConstructorOptions } from '@visactor/vtable'
import { useTranslation } from 'react-i18next'
import EmptyState from '../EmptyState'
import type { Accent } from '../PageHeader'
import { FONTE, TOKENS } from './tokens'

// A célula de progresso é opcional na VTable e entra por registro, uma vez por aplicação.
registerProgressBarCell()

type ColunaDaVTable = NonNullable<ListTableConstructorOptions['columns']>[number]
export type Registro = Record<string, unknown>

/**
 * Uma coluna: os campos da VTable (`field`, `width`, `style`, `customLayout`, `icon`, `cellType`…), com o título em
 * texto — a tabela o mostra em caixa alta e o espelho acessível o escreve. `fieldFormat` é o texto da célula nos dois
 * lugares; `espelho`, o conteúdo da célula no espelho quando o texto não basta (um botão que no canvas é um ícone).
 *
 * Os campos comuns são tipados aqui e o resto passa direto para a VTable (assinatura de índice). Não é a interseção
 * com o tipo de coluna da VTable de propósito: aquele tipo é uma união, e numa interseção com união o TypeScript
 * perde o tipo do parâmetro de `fieldFormat: (r) => …` (vira `any` implícito em toda tela).
 */
export interface VxColumn {
    field: string
    title: string
    width?: number | string
    minWidth?: number | string
    maxWidth?: number | string
    fieldFormat?: (registro: Registro) => string
    style?: unknown
    espelho?: (registro: Registro, indice: number) => ReactNode
    /** Preenchido pela `colunaAcoes`: no espelho, cada ação vira um <button>. */
    acoes?: { nome: string; rotulo: string; mostrar?: (registro: Registro) => boolean }[]
    [opcaoDaVTable: string]: unknown
}

export interface VxTableProps<R extends Registro = Registro> {
    records: R[]
    columns: VxColumn[]
    /** O nome da tabela para quem não a vê (a `caption` do espelho acessível). */
    legenda: string
    /** Clique na linha, ou Enter na célula escolhida pelo teclado; `campo` é a coluna da célula. */
    onRowClick?: (registro: R, indice: number, campo?: string) => void
    /** Clique num ícone de célula (`column.icon`, pelo `name`): não vira clique da linha. */
    onIconClick?: (nome: string, registro: R, indice: number) => void
    loading?: boolean
    error?: string | null
    onRetry?: () => void
    emptyMessage?: ReactNode
    emptyDescription?: ReactNode
    emptyIcon?: ReactNode
    emptyAccent?: Accent
    emptyAction?: ReactNode
    rowHeight?: number
    headerHeight?: number
    /** Sem `height`, a tabela cresce com as linhas até aqui e depois rola por dentro. */
    maxHeight?: number
    height?: number
    frozenColCount?: number
    rightFrozenColCount?: number
    /** Linhas de duas alturas (célula com duas linhas de texto). */
    alturaDaLinha?: (registro: R) => number | undefined
    /** `data-testid` do espelho, e de cada linha dele (por onde os testes leem e clicam). */
    testid?: string
    rowTestId?: (registro: R) => string
}

/** O ambiente de teste (jsdom) não desenha canvas: lá a tabela nem nasce, e o teste lê o espelho, que diz o mesmo. */
const SEM_CANVAS = typeof navigator !== 'undefined' && /jsdom/i.test(navigator.userAgent)

/** A coluna como a VTable a recebe: sem os campos deste wrapper, com o título em caixa alta. */
function paraVTable(coluna: VxColumn): ColunaDaVTable {
    const c: Record<string, unknown> = { ...coluna, title: coluna.title.toUpperCase() }
    delete c.espelho
    delete c.acoes
    return c as ColunaDaVTable
}

const textoDaCelula = (c: VxColumn, r: Registro) =>
    c.fieldFormat ? c.fieldFormat(r) : r[c.field] === null || r[c.field] === undefined ? '' : String(r[c.field])

/**
 * A tabela do archflow, sobre a VTable (`@visactor/vtable`) — o mesmo padrão do `VxTable` do vendax-delivery e do
 * `RqTable` do gestor-rq-admin: o núcleo canvas direto (o pacote para React lê `ReactCurrentOwner`, removido no
 * React 19), nas cores e na fonte do archflow, claro e escuro.
 *
 * O que este wrapper acrescenta: altura automática (cresce com as linhas até `maxHeight`, depois rola por dentro) e
 * os três estados em HTML — carregando (esqueleto), vazio (`EmptyState`) e erro com "tentar de novo" —, os mesmos que
 * a antiga `DataTable` dava.
 *
 * **O canvas não se lê.** Leitor de tela, busca do navegador e os testes não enxergam nada dentro dele. Por isso a
 * tabela sai duas vezes: o canvas, escondido de tecnologia assistiva, e um `<table>` de verdade, visualmente
 * escondido, com os mesmos textos — ele leva o `data-testid` e recebe o clique da linha. Os dois saem das mesmas
 * colunas, então não divergem.
 */
export function VxTable<R extends Registro = Registro>(props: VxTableProps<R>) {
    const { t } = useTranslation()
    const {
        records, columns, legenda, onRowClick, loading = false, error = null, onRetry,
        emptyMessage, emptyDescription, emptyIcon, emptyAccent, emptyAction,
        rowHeight = 46, headerHeight = 40, maxHeight = 560, height, testid, rowTestId,
    } = props

    if (error) {
        return (
            <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />} m="md">
                <Group justify="space-between" wrap="nowrap" gap="sm">
                    <Text size="sm">{error}</Text>
                    {onRetry && <Button size="xs" variant="white" color="red" onClick={onRetry}>{t('common.refresh')}</Button>}
                </Group>
            </Alert>
        )
    }

    if (loading && records.length === 0) {
        return (
            <Stack gap={0} aria-busy="true" aria-label={legenda}>
                <div style={{ height: headerHeight, background: 'var(--table-head-bg)', borderBottom: '1px solid var(--surface-border)' }} />
                {Array.from({ length: 5 }).map((_, i) => (
                    <Group key={i} h={rowHeight} px="md" gap="md" wrap="nowrap" style={{ borderBottom: '1px solid var(--surface-border)' }}>
                        {columns.slice(0, 5).map((c) => <Skeleton key={c.field} height={12} radius="sm" style={{ flex: 1 }} />)}
                    </Group>
                ))}
            </Stack>
        )
    }

    if (records.length === 0) {
        return (
            <>
                <EmptyState
                    compact
                    icon={emptyIcon ?? <IconInbox />}
                    accent={emptyAccent}
                    title={emptyMessage ?? t('common.noData')}
                    description={emptyDescription}
                >
                    {emptyAction && <Group justify="center" mt="xs">{emptyAction}</Group>}
                </EmptyState>
                <VisuallyHidden>
                    <table data-testid={testid}><caption>{legenda}</caption><tbody /></table>
                </VisuallyHidden>
            </>
        )
    }

    const altura = height ?? Math.min(maxHeight, headerHeight + records.length * rowHeight + 2)

    return (
        <div style={{ position: 'relative', height: altura, minHeight: 0 }}>
            <Canvas {...props} altura={altura} />
            <VisuallyHidden>
                <table data-testid={testid}>
                    <caption>{legenda}</caption>
                    <thead>
                        <tr>{columns.map((c) => <th key={c.field} scope="col">{c.title}</th>)}</tr>
                    </thead>
                    <tbody>
                        {records.map((r, i) => (
                            <tr key={i} data-testid={rowTestId?.(r)}
                                onClick={onRowClick ? () => onRowClick(r, i) : undefined}>
                                {columns.map((c) => (
                                    <td key={c.field}>
                                        {c.acoes
                                            ? c.acoes.filter((a) => a.mostrar?.(r) ?? true).map((a) => (
                                                <button key={a.nome} type="button"
                                                        onClick={(e) => { e.stopPropagation(); props.onIconClick?.(a.nome, r, i) }}>
                                                    {a.rotulo}
                                                </button>
                                            ))
                                            : c.espelho ? c.espelho(r, i) : textoDaCelula(c, r)}
                                    </td>
                                ))}
                            </tr>
                        ))}
                    </tbody>
                </table>
            </VisuallyHidden>
        </div>
    )
}

/** O canvas: nasce ao montar (só quando há linhas), repinta quando dados, colunas ou tema mudam. */
function Canvas<R extends Registro>({
    records, columns, onRowClick, onIconClick, rowHeight = 46, headerHeight = 40,
    frozenColCount = 0, rightFrozenColCount = 0, alturaDaLinha, altura,
}: VxTableProps<R> & { altura: number }) {
    const escuro = useComputedColorScheme('light') === 'dark'
    const containerRef = useRef<HTMLDivElement>(null)
    const tabelaRef = useRef<ListTable | null>(null)
    // A tabela nasce uma vez; os eventos leem daqui os dados e os manipuladores de agora, não os da criação.
    const recordsRef = useRef(records)
    recordsRef.current = records
    const columnsRef = useRef(columns)
    columnsRef.current = columns
    const onRowClickRef = useRef(onRowClick)
    onRowClickRef.current = onRowClick
    const onIconClickRef = useRef(onIconClick)
    onIconClickRef.current = onIconClick
    const alturaDaLinhaRef = useRef(alturaDaLinha)
    alturaDaLinhaRef.current = alturaDaLinha
    const escolhidaRef = useRef<{ col: number; row: number } | null>(null)

    const opcoes = useMemo((): ListTableConstructorOptions => {
        const tk = escuro ? TOKENS.dark : TOKENS.light
        return {
            records,
            // `espelho` e `acoes` são deste wrapper; a VTable recebe o resto, com o título em caixa alta.
            columns: columns.map(paraVTable),
            frozenColCount,
            rightFrozenColCount,
            defaultRowHeight: rowHeight,
            defaultHeaderRowHeight: headerHeight,
            hover: { highlightMode: 'row', disableHeaderHover: true },
            select: { highlightMode: 'row', headerSelectMode: 'cell', disableHeaderSelect: true },
            keyboardOptions: { moveSelectedCellOnArrowKeys: true, moveFocusCellOnTab: true, copySelected: true },
            theme: {
                defaultStyle: {
                    fontFamily: FONTE, fontSize: 13, color: tk.text, borderColor: tk.border, borderLineWidth: 1, bgColor: tk.surface,
                },
                headerStyle: {
                    fontFamily: FONTE, fontSize: 10.5, fontWeight: 600, color: tk.muted, bgColor: tk.header,
                    borderColor: tk.border, borderLineWidth: [0, 0, 1, 0], textAlign: 'left', padding: [6, 16, 6, 16],
                },
                bodyStyle: {
                    fontFamily: FONTE, fontSize: 13, color: tk.text, borderColor: tk.border, borderLineWidth: [0, 0, 1, 0],
                    padding: [8, 16, 8, 16], bgColor: tk.surface,
                    hover: { cellBgColor: tk.hover },
                },
                frameStyle: { borderLineWidth: 0, borderColor: 'transparent', cornerRadius: 0 },
                selectionStyle: { cellBgColor: tk.selected, cellBorderColor: 'transparent', cellBorderLineWidth: 0 },
                underlayBackgroundColor: tk.surface,
                scrollStyle: { visible: 'scrolling', width: 8, scrollSliderColor: tk.border },
            },
            widthMode: 'standard',
            autoFillWidth: true,
            autoWrapText: false,
            tooltip: { isShowOverflowTextTooltip: true, confine: true },
        } as unknown as ListTableConstructorOptions
    }, [records, columns, escuro, frozenColCount, rightFrozenColCount, rowHeight, headerHeight])

    function aplicarAlturas() {
        const fn = alturaDaLinhaRef.current
        const tabela = tabelaRef.current
        if (!fn || !tabela) return
        try {
            recordsRef.current.forEach((r, i) => tabela.setRowHeight(i + 1, fn(r) ?? rowHeight))
        } catch { /* sem tabela, sem o que atualizar */ }
    }

    // Cria a instância uma única vez. Sem canvas (jsdom), fica só o espelho.
    useEffect(() => {
        if (!containerRef.current || SEM_CANVAS) return
        let tabela: ListTable
        try {
            tabela = new ListTable(containerRef.current, opcoes)
        } catch {
            return
        }
        tabelaRef.current = tabela
        aplicarAlturas()

        const campoDa = (col: number) => columnsRef.current[col]?.field
        tabela.on('click_cell', (args: { col: number; row: number; targetIcon?: { name?: string } }) => {
            const indice = args.row - 1 // a linha 0 é o cabeçalho
            const registro = recordsRef.current[indice]
            if (registro === undefined) return
            if (args.targetIcon?.name && onIconClickRef.current) {
                onIconClickRef.current(args.targetIcon.name, registro, indice)
                return
            }
            onRowClickRef.current?.(registro, indice, campoDa(args.col))
        })
        tabela.on('selected_cell', (args: { col: number; row: number }) => {
            escolhidaRef.current = { col: args.col, row: args.row }
        })
        // Enter na célula escolhida pelo teclado aciona a linha, como o clique.
        tabela.on('keydown', (args: { code?: string; key?: string; event?: KeyboardEvent }) => {
            const tecla = args.event?.key ?? args.key ?? args.code
            const escolhida = escolhidaRef.current
            if (tecla !== 'Enter' || !escolhida) return
            const registro = recordsRef.current[escolhida.row - 1]
            if (registro !== undefined) onRowClickRef.current?.(registro, escolhida.row - 1, campoDa(escolhida.col))
        })

        const observador = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => {
            try { tabela.resize() } catch { /* a tabela pode já ter sido liberada */ }
        })
        observador?.observe(containerRef.current)
        return () => {
            observador?.disconnect()
            tabela.release()
            tabelaRef.current = null
        }
        // A instância nasce uma vez; as mudanças entram pelo efeito de baixo.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    // Dados, colunas ou tema novos: repinta — o canvas não herda nada do CSS.
    useEffect(() => {
        try { tabelaRef.current?.updateOption(opcoes) } catch { /* sem tabela, sem o que atualizar */ }
        aplicarAlturas()
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [opcoes])

    // A altura mudou (mais ou menos linhas): o canvas acompanha a caixa.
    useEffect(() => {
        try { tabelaRef.current?.resize() } catch { /* idem */ }
    }, [altura])

    // A roda do mouse para na tabela quando ela rola por dentro: `passive: false` para o preventDefault valer, e
    // `capture` porque a VTable para a propagação no canvas (as duas descobertas do gestor-rq).
    const rolaPorDentro = altura < headerHeight + records.length * rowHeight
    useEffect(() => {
        const elemento = containerRef.current
        if (!rolaPorDentro || !elemento) return
        const segurar = (evento: WheelEvent) => evento.preventDefault()
        elemento.addEventListener('wheel', segurar, { passive: false, capture: true })
        return () => elemento.removeEventListener('wheel', segurar, { capture: true })
    }, [rolaPorDentro])

    return (
        <div ref={containerRef} aria-hidden="true"
             style={{ width: '100%', height: '100%', cursor: onRowClick ? 'pointer' : undefined }} />
    )
}
