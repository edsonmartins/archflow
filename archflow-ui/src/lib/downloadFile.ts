import { authFetch } from '../services/api'
import { mensagemDoCorpoDeErro } from './errorMessage'

/**
 * Baixa um arquivo de uma rota da API com a sessão do usuário e o entrega ao navegador.
 *
 * Os botões "Exportar CSV" (cobrança e auditoria) eram `<a href>`: uma navegação não leva o cabeçalho
 * `Authorization`, que é o único lugar em que o `JwtAuthenticationFilter` procura o token. Funcionavam
 * só com a autenticação desligada; com ela ligada, o arquivo salvo seria o JSON do 401 (RCHFLW-195
 * item 9). Aqui o download passa pelo `authFetch` (token atualizado, personificação) e vira um blob.
 *
 * Uma resposta de erro não vira arquivo: lança com a frase do corpo, para a tela mostrar.
 */
export async function baixarArquivo(url: string, nomeDoArquivo: string): Promise<void> {
    const resposta = await authFetch(url)
    if (!resposta.ok) {
        const corpo = await resposta.text().catch(() => '')
        throw new Error(mensagemDoCorpoDeErro(corpo, resposta.statusText || `HTTP ${resposta.status}`))
    }
    const blob = await resposta.blob()
    const href = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = href
    link.download = nomeDoArquivo
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    link.remove()
    // Revogar no mesmo tique pode cancelar o download em alguns navegadores; o blob só precisa viver
    // até o navegador começar a salvá-lo.
    setTimeout(() => URL.revokeObjectURL(href), 10_000)
}
