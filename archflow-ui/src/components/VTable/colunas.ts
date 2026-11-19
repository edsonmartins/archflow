import { CustomLayout } from '@visactor/vtable'
import pencil from '@tabler/icons/outline/pencil.svg?raw'
import trash from '@tabler/icons/outline/trash.svg?raw'
import copy from '@tabler/icons/outline/copy.svg?raw'
import eye from '@tabler/icons/outline/eye.svg?raw'
import play from '@tabler/icons/outline/player-play.svg?raw'
import stop from '@tabler/icons/outline/player-stop.svg?raw'
import external from '@tabler/icons/outline/external-link.svg?raw'
import refresh from '@tabler/icons/outline/refresh.svg?raw'
import ban from '@tabler/icons/outline/ban.svg?raw'
import check from '@tabler/icons/outline/check.svg?raw'
import x from '@tabler/icons/outline/x.svg?raw'
import key from '@tabler/icons/outline/key.svg?raw'
import dots from '@tabler/icons/outline/dots.svg?raw'
import { FONTE, selo, tokens } from './tokens'
import type { Registro, VxColumn } from './VxTable'

/**
 * Colunas prontas da `VxTable`, desenhadas no canvas: selo de status, texto em duas linhas, barra de progresso,
 * texto técnico apagado e ações por ícone. Todas leem o tema na hora da pintura (`tokens()`), então acompanham
 * claro/escuro quando a tabela repinta.
 */

type ArgsDeLayout = {
    table: { getCellRect(col: number, row: number): { width: number; height: number }; records?: Registro[] }
    row: number
    col: number
    rect?: { width: number; height: number }
    value?: unknown
}

type Filho = { addChild(filho: unknown): void }

const registroDa = (args: ArgsDeLayout) => args.table.records?.[args.row - 1] ?? {}
const caixa = (args: ArgsDeLayout) => args.rect ?? args.table.getCellRect(args.col, args.row)

/**
 * Selo de status: ponto + texto num fundo suave da cor do estado. `cor` recebe o registro inteiro e devolve uma cor
 * Mantine (green, red, orange, blue, gray…) — a mesma que a tela passaria ao `StatusBadge`. A cor nunca vai sozinha:
 * o selo sempre leva o texto.
 */
export function colunaStatus(coluna: VxColumn, cor: (registro: Registro) => string | undefined): VxColumn {
    return {
        width: 150,
        ...coluna,
        customLayout: (args: ArgsDeLayout) => {
            const { width, height } = caixa(args)
            const registro = registroDa(args)
            const texto = coluna.fieldFormat ? coluna.fieldFormat(registro) : String(args.value ?? '')
            const s = selo(cor(registro))
            const raiz = new CustomLayout.Group({ width, height, display: 'flex', alignItems: 'center' }) as unknown as Filho
            if (texto) {
                raiz.addChild(new CustomLayout.Tag({
                    text: `●  ${texto}`,
                    textStyle: { fontSize: 11.5, fontWeight: 600, fontFamily: FONTE, fill: s.tinta },
                    padding: [3, 9],
                    panel: { visible: true, fill: s.fundo, cornerRadius: 999 },
                    marginLeft: 16,
                }))
            }
            return { rootContainer: raiz, renderDefault: false }
        },
    } as VxColumn
}

/** Texto em duas linhas: o principal em destaque, o secundário menor e apagado (nome + e-mail, fluxo + id…). */
export function colunaDupla(
    coluna: VxColumn,
    principal: (registro: Registro) => string,
    secundaria: (registro: Registro) => string | undefined,
): VxColumn {
    return {
        fieldFormat: (r: Registro) => [principal(r), secundaria(r)].filter(Boolean).join(' · '),
        ...coluna,
        customLayout: (args: ArgsDeLayout) => {
            const { width, height } = caixa(args)
            const registro = registroDa(args)
            const tk = tokens()
            const raiz = new CustomLayout.Group({
                width, height, display: 'flex', flexDirection: 'column', justifyContent: 'center',
            }) as unknown as Filho
            raiz.addChild(new CustomLayout.Text({
                text: principal(registro), fontSize: 13, fontWeight: 600, fontFamily: FONTE, fill: tk.text,
                marginLeft: 16, marginRight: 16, maxLineWidth: width - 32, ellipsis: '…',
            }))
            const sub = secundaria(registro)
            if (sub) {
                raiz.addChild(new CustomLayout.Text({
                    text: sub, fontSize: 11.5, fontFamily: FONTE, fill: tk.muted,
                    marginLeft: 16, marginRight: 16, marginTop: 3, maxLineWidth: width - 32, ellipsis: '…',
                }))
            }
            return { rootContainer: raiz, renderDefault: false }
        },
    } as VxColumn
}

/** Texto técnico (ids, datas, durações): menor e apagado, para o olho ir primeiro ao que importa. */
export function colunaApagada(coluna: VxColumn): VxColumn {
    return {
        ...coluna,
        style: (args: unknown) => {
            const proprio = typeof coluna.style === 'function'
                ? (coluna.style as (a: unknown) => object)(args)
                : (coluna.style as object | undefined)
            return { color: tokens().muted, fontSize: 12, ...proprio }
        },
    } as VxColumn
}

/**
 * Barra de 0 a 100 embaixo do número (a célula de progresso da VTable). `cor` muda a cor da barra por registro
 * (ex.: vermelha acima de 90%). O texto da célula é o `fieldFormat` — padrão "N%".
 */
export function colunaBarra(coluna: VxColumn, cor?: (registro: Registro) => string | undefined): VxColumn {
    const tinta = (args: { table: { records?: Registro[] }; row: number }) => {
        const c = cor?.(args.table.records?.[args.row - 1] ?? {})
        return c ? selo(c).tinta : tokens().accent
    }
    return {
        fieldFormat: (r: Registro) => (r[coluna.field] === null || r[coluna.field] === undefined ? '' : `${r[coluna.field]}%`),
        ...coluna,
        cellType: 'progressbar',
        min: 0,
        max: 100,
        style: {
            barHeight: 5,
            barPositionInCell: 'bottom',
            barColor: tinta,
            barBgColor: () => tokens().barBg,
            showBar: true,
            ...(coluna.style as object | undefined),
        },
    } as unknown as VxColumn
}

const SVGS = { pencil, trash, copy, eye, play, stop, external, refresh, ban, check, x, key, dots } as const
export type IconeDeAcao = keyof typeof SVGS

/** O SVG da Tabler na cor pedida — o canvas não entende `currentColor`. */
function svg(icone: IconeDeAcao, cor: string) {
    return SVGS[icone].replace(/currentColor/g, cor).replace(/stroke-width="2"/, 'stroke-width="1.8"')
}

export interface Acao {
    /** O nome que chega no `onIconClick`. */
    nome: string
    icone: IconeDeAcao
    /** Texto do tooltip e do botão no espelho acessível. */
    rotulo: string
    /** Cor Mantine do ícone (red para excluir…). Padrão: apagado. */
    cor?: string
    /** Esconde a ação em linhas onde ela não vale. */
    mostrar?: (registro: Registro) => boolean
}

/**
 * Coluna de ações: ícones à direita da linha. O clique chega no `onIconClick` da tabela pelo `nome` e não vira
 * clique da linha. No espelho acessível cada ação é um botão de verdade, com o rótulo.
 */
export function colunaAcoes(acoes: Acao[], coluna?: Partial<VxColumn>): VxColumn {
    return {
        field: '__acoes',
        title: '',
        width: Math.max(72, acoes.length * 32 + 24),
        ...coluna,
        icon: (args: { table: { records?: Registro[] }; row: number }) => {
            const registro = args.table.records?.[args.row - 1] ?? {}
            return acoes.filter((a) => a.mostrar?.(registro) ?? true).map((a) => ({
                type: 'svg',
                svg: svg(a.icone, a.cor ? selo(a.cor).tinta : tokens().muted),
                name: a.nome,
                width: 17,
                height: 17,
                marginLeft: 6,
                marginRight: 6,
                positionType: 'left',
                cursor: 'pointer',
                tooltip: { title: a.rotulo, placement: 'top' },
                hover: { width: 28, height: 28, bgColor: tokens().hover, cornerRadius: 6 },
            }))
        },
        fieldFormat: () => '',
        // O espelho acessível desenha estas ações como <button> que chamam o mesmo onIconClick (VxTable).
        acoes,
    } as unknown as VxColumn
}
