package br.com.archflow.langchain4j.mcp.client;

/**
 * A correlação que acompanha as chamadas MCP de uma execução de agente.
 *
 * <h2>Por que existe</h2>
 *
 * <p>O server (VendaX Core) grava um evento por cotação montada, para responder "o que aconteceu
 * naquela cotação de terça". Medido em 05/08: a fronteira MCP carregava <b>só</b>
 * {@code X-TENANT-ID}, e a tool {@code montar_cotacao} recebe apenas {@code clienteRef} — então
 * não havia como ligar a cotação à conversa nem à execução que a produziu.</p>
 *
 * <h2>Por que header, e não argumento de tool</h2>
 *
 * <p>Argumento de tool é o <b>modelo</b> quem preenche. Em 05/08, no Core, um campo de fator
 * exposto ao agente foi preenchido com a quantidade falada e envenenou um léxico permanente.
 * Correlação é do transporte: entra por header, e nada entre o dispatcher e o server pode
 * alterá-la.</p>
 *
 * <h2>ThreadLocal, com captura na borda</h2>
 *
 * <p>O dispatcher define isto na thread que executa o agente. As chamadas de tool, porém, rodam em
 * <b>threads virtuais do executor de I/O</b> do client — onde este ThreadLocal não existe. Por isso
 * {@code HttpMcpClient.callTool} <b>captura o valor na thread do chamador</b> e o passa adiante
 * como parâmetro, em vez de ler dentro do trabalho assíncrono. Ler lá dentro compilaria, devolveria
 * nulo sempre, e ninguém notaria — a correlação simplesmente não apareceria.</p>
 */
public final class CorrelacaoMcp {

    /** {@code QP:<conversa>:<seq>} — a chave da janela do pedido; dela o server extrai a conversa. */
    public static final String HEADER_JANELA = "X-Vendax-Janela";

    /** A execução específica, para cruzar o log do ArchFlow com o evento do server. */
    public static final String HEADER_TRACE = "X-Vendax-Trace";

    private static final ThreadLocal<Dados> ATUAL = new ThreadLocal<>();

    public record Dados(String janelaChave, String traceId) {
        public boolean vazio() {
            return (janelaChave == null || janelaChave.isBlank())
                    && (traceId == null || traceId.isBlank());
        }
    }

    private CorrelacaoMcp() {
    }

    public static void definir(String janelaChave, String traceId) {
        ATUAL.set(new Dados(janelaChave, traceId));
    }

    /** Nunca nulo: ausência é o caso normal (initialize, tools/list, testes). */
    public static Dados atual() {
        Dados d = ATUAL.get();
        return d == null ? new Dados(null, null) : d;
    }

    public static void limpar() {
        ATUAL.remove();
    }
}
