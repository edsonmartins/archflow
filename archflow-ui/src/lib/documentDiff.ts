/**
 * Diferença linha a linha entre dois documentos de fluxo — o "Comparar versões" do Publicar.
 *
 * <p>Antes a tela comparava a linha N de um lado com a linha N do outro (RCHFLW-148): inserir um único campo
 * deslocava todo o resto e marcava quase o documento inteiro como alterado (353 de 375 linhas no caso do QA).
 * Aqui as linhas são alinhadas pelo algoritmo de Myers — a menor sequência de inserções e remoções, a mesma
 * ideia do `diff`/`git diff` —, então só o que mudou de fato aparece com `-`/`+`.</p>
 */

export type DiffOp = ' ' | '-' | '+'

export interface DiffLine {
    op: DiffOp
    line: string
}

/**
 * As linhas de `a` e `b` alinhadas: `' '` presente nos dois, `'-'` só em `a`, `'+'` só em `b`. Numa troca, as
 * remoções vêm antes das inserções, como no `diff -u`.
 *
 * <p>Myers O((N+M)·D), guardando só a faixa da diagonal usada em cada passo: com versões do mesmo fluxo D é
 * pequeno, e o prefixo e o sufixo comuns saem antes, sem custo.</p>
 */
export function diffLines(a: readonly string[], b: readonly string[]): DiffLine[] {
    let start = 0
    while (start < a.length && start < b.length && a[start] === b[start]) start += 1
    let endA = a.length
    let endB = b.length
    while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
        endA -= 1
        endB -= 1
    }
    const middle = myers(a.slice(start, endA), b.slice(start, endB))
    return [
        ...a.slice(0, start).map(line => ({ op: ' ' as const, line })),
        ...middle,
        ...a.slice(endA).map(line => ({ op: ' ' as const, line })),
    ]
}

function myers(a: readonly string[], b: readonly string[]): DiffLine[] {
    const n = a.length
    const m = b.length
    if (n === 0) return b.map(line => ({ op: '+' as const, line }))
    if (m === 0) return a.map(line => ({ op: '-' as const, line }))
    const max = n + m
    const offset = max + 1
    // v[k + offset] = o x mais distante alcançado na diagonal k. trace[d] é a cópia de v antes do passo d.
    const v = new Int32Array(2 * max + 3)
    const trace: Int32Array[] = []
    let found = -1
    for (let d = 0; d <= max && found < 0; d += 1) {
        trace.push(v.slice(offset - d - 1, offset + d + 2))
        for (let k = -d; k <= d; k += 2) {
            let x = k === -d || (k !== d && v[offset + k - 1] < v[offset + k + 1])
                ? v[offset + k + 1]
                : v[offset + k - 1] + 1
            let y = x - k
            while (x < n && y < m && a[x] === b[y]) {
                x += 1
                y += 1
            }
            v[offset + k] = x
            if (x >= n && y >= m) {
                found = d
                break
            }
        }
    }
    // Volta do fim ao começo pelo caminho escolhido em cada passo.
    const out: DiffLine[] = []
    let x = n
    let y = m
    for (let d = found; d >= 0; d -= 1) {
        const saved = trace[d]
        // saved cobre as diagonais [-d-1, d+1]: o índice de k nela é k + d + 1.
        const at = (k: number) => saved[k + d + 1]
        const k = x - y
        const prevK = k === -d || (k !== d && at(k - 1) < at(k + 1)) ? k + 1 : k - 1
        const prevX = d === 0 ? 0 : at(prevK)
        const prevY = d === 0 ? 0 : prevX - prevK
        while (x > prevX && y > prevY) {
            out.push({ op: ' ', line: a[x - 1] })
            x -= 1
            y -= 1
        }
        if (d > 0) {
            if (x === prevX) out.push({ op: '+', line: b[y - 1] })
            else out.push({ op: '-', line: a[x - 1] })
        }
        x = prevX
        y = prevY
    }
    out.reverse()
    // O caminho de Myers pode intercalar `+` e `-` de uma mesma troca; agrupa cada bloco com as remoções antes.
    const grouped: DiffLine[] = []
    let removed: DiffLine[] = []
    let added: DiffLine[] = []
    const flush = () => {
        grouped.push(...removed, ...added)
        removed = []
        added = []
    }
    for (const entry of out) {
        if (entry.op === '-') removed.push(entry)
        else if (entry.op === '+') added.push(entry)
        else {
            flush()
            grouped.push(entry)
        }
    }
    flush()
    return grouped
}

/**
 * O texto do "Comparar versões": o JSON das duas versões, linha a linha, com `  `/`- `/`+ ` na frente, e se houve
 * alguma diferença.
 */
export function compareDocuments(left?: unknown, right?: unknown): { changed: boolean; text: string } {
    if (!left || !right) return { changed: false, text: '' }
    const lines = diffLines(JSON.stringify(left, null, 2).split('\n'), JSON.stringify(right, null, 2).split('\n'))
    return {
        changed: lines.some(entry => entry.op !== ' '),
        text: lines.map(entry => `${entry.op} ${entry.line}`).join('\n'),
    }
}
