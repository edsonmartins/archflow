import { describe, expect, it } from 'vitest'
import { compareDocuments, diffLines } from '../documentDiff'

// RCHFLW-148: o "Comparar versões" comparava a linha N com a linha N. Um campo inserido deslocava o resto do
// documento e quase tudo aparecia como alterado.

function marked(text: string) {
    return text.split('\n').filter(line => line.startsWith('- ') || line.startsWith('+ '))
}

/** Reconstrói os dois lados a partir do diff: o alinhamento não pode perder nem inventar linha. */
function sides(lines: ReturnType<typeof diffLines>) {
    return {
        left: lines.filter(l => l.op !== '+').map(l => l.line),
        right: lines.filter(l => l.op !== '-').map(l => l.line),
    }
}

describe('compareDocuments (RCHFLW-148)', () => {
    const v1 = {
        id: 'wf-9d1c9fcc',
        steps: [
            {
                id: 'node-1795665204798',
                type: 'input',
                config: {
                    fields: [
                        { name: 'cliente_nome', type: 'string' },
                        { name: 'valor', type: 'number' },
                        { name: 'motivo_devolução', type: 'string' },
                    ],
                },
            },
            {
                id: 'cond',
                type: 'condition',
                config: { expression: '${node-1795665204798.valor} >= 1000' },
                connections: [{ targetId: 'out', branch: 'true' }],
            },
            { id: 'out', type: 'output', label: 'Saída final' },
        ],
    }

    it('um campo inserido e uma expressão trocada marcam só essas linhas', () => {
        const v3 = structuredClone(v1)
        v3.steps[0].config!.fields!.splice(0, 0, { name: 'cliente_email', type: 'string' })
        v3.steps[1].config!.expression = '${node-1795665204798.valor} >= 2000'

        const { changed, text } = compareDocuments(v1, v3)

        expect(changed).toBe(true)
        // O objeto inserido (4 linhas: {, name, type, }) e a expressão (- e +). Nada mais: o antigo marcava 82
        // linhas num documento assim, todas as que só se deslocaram.
        const linhas = marked(text)
        expect(linhas).toHaveLength(6)
        expect(linhas.filter(l => l.startsWith('+ '))).toHaveLength(5)
        expect(linhas).toContain('+             "name": "cliente_email",')
        expect(linhas).toContain('-         "expression": "${node-1795665204798.valor} >= 1000"')
        expect(linhas).toContain('+         "expression": "${node-1795665204798.valor} >= 2000"')
        expect(linhas.some(l => /cliente_nome|"valor"|motivo_devolução|Saída final/.test(l))).toBe(false)
    })

    it('versões iguais não marcam nada', () => {
        const { changed, text } = compareDocuments(v1, structuredClone(v1))
        expect(changed).toBe(false)
        expect(marked(text)).toEqual([])
    })

    it('sem uma das versões não há o que comparar', () => {
        expect(compareDocuments(undefined, v1)).toEqual({ changed: false, text: '' })
    })
})

describe('diffLines', () => {
    it('alinha inserções, remoções e trocas sem perder linha dos dois lados', () => {
        const a = ['a', 'b', 'c', 'd', 'e', 'f', 'g']
        const b = ['a', 'x', 'c', 'd', 'y', 'z', 'f', 'g', 'h']
        const lines = diffLines(a, b)
        expect(sides(lines)).toEqual({ left: a, right: b })
        expect(lines.filter(l => l.op !== ' ').map(l => `${l.op}${l.line}`))
            .toEqual(['-b', '+x', '-e', '+y', '+z', '+h'])
    })

    it('lados vazios viram só inserção ou só remoção', () => {
        expect(diffLines([], ['a', 'b'])).toEqual([{ op: '+', line: 'a' }, { op: '+', line: 'b' }])
        expect(diffLines(['a'], [])).toEqual([{ op: '-', line: 'a' }])
        expect(diffLines([], [])).toEqual([])
    })

    it('documentos inteiros diferentes continuam reconstruíveis', () => {
        const a = Array.from({ length: 300 }, (_, i) => `a${i}`)
        const b = Array.from({ length: 280 }, (_, i) => (i % 7 === 0 ? `a${i}` : `b${i}`))
        expect(sides(diffLines(a, b))).toEqual({ left: a, right: b })
    })
})
