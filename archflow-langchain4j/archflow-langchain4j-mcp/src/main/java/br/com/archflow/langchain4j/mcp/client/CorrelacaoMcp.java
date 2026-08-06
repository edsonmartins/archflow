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

    /**
     * As chaves pelas quais a correlação atravessa a fronteira de THREAD.
     *
     * <p>Medido em 06/08, no log de produção: o dispatcher roda em {@code [vendax-agent-4]} e a
     * execução do passo em {@code [virtual-129]}. Definir o ThreadLocal no dispatcher, como a
     * primeira versão fazia, não alcançava o {@code callTool} — o header nunca era enviado, e
     * <b>nada falhava</b>: a correlação simplesmente não aparecia.</p>
     *
     * <p>Por isso ela viaja no contexto do fluxo, que atravessa threads, e é reposta no ThreadLocal
     * pelo componente que executa o agente — já na thread certa.</p>
     */
    public static final String CTX_JANELA = "vendax.correlacao.janela";
    public static final String CTX_TRACE = "vendax.correlacao.trace";

    private static final ThreadLocal<Dados> ATUAL = new ThreadLocal<>();

    public record Dados(String janelaChave, String traceId) {
        public boolean vazio() {
            return (janelaChave == null || janelaChave.isBlank())
                    && (traceId == null || traceId.isBlank());
        }
    }

    private CorrelacaoMcp() {
    }

    /**
     * Define a correlação da thread atual. <b>Sem correlação, LIMPA</b> — não deixa o valor
     * anterior.
     *
     * <p>Retornar sem tocar no ThreadLocal quando não há o que definir deixaria a execução seguinte
     * herdando a correlação da anterior em qualquer caminho que esquecesse o {@code finally}. A
     * ausência é um valor, e escrevê-la é mais barato que confiar em disciplina.</p>
     */
    public static void definir(String janelaChave, String traceId) {
        Dados d = new Dados(janelaChave, traceId);
        if (d.vazio()) {
            ATUAL.remove();
            return;
        }
        ATUAL.set(d);
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
