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
     * O cliente da conversa — <b>identidade</b>, não correlação.
     *
     * <h2>Por que precisa vir por aqui</h2>
     *
     * <p>O invoke carrega {@code customerRef}: o ArchFlow <b>sabe</b> de quem é a conversa. Mas a
     * fronteira MCP transportava só o tenant, a janela e o trace, e o {@code clienteRef} chegava ao
     * server como <b>argumento que o modelo preenche</b>, em toda tool.
     *
     * <p>Auditoria de 07/08: se o modelo copiar o cliente errado, tudo fica internamente coerente
     * <i>para o cliente errado</i> — léxico, prior, fator e prova usam o mesmo ref trocado, e
     * nenhuma guarda do server tem como notar, porque a única fonte da identidade é a afirmação do
     * modelo. Era a última identidade que ainda atravessava o contexto de um LLM neste sistema.
     *
     * <p>A diferença para a janela e o trace, que estão logo acima, é de consequência: correlação
     * errada estraga um relatório, identidade errada escreve no cadastro de outra pessoa. O
     * mecanismo é o mesmo — o valor sai do invoke e nada entre o dispatcher e o server pode
     * alterá-lo — mas o motivo de ele existir não é rastreabilidade, é integridade.
     */
    public static final String HEADER_CLIENTE = "X-Vendax-Cliente";

    /** O vendedor da conversa; mesma origem e mesmas razões do {@link #HEADER_CLIENTE}. */
    public static final String HEADER_VENDEDOR = "X-Vendax-Vendedor";

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
    public static final String CTX_CLIENTE = "vendax.identidade.cliente";
    public static final String CTX_VENDEDOR = "vendax.identidade.vendedor";

    private static final ThreadLocal<Dados> ATUAL = new ThreadLocal<>();

    /**
     * O que acompanha a execução: correlação (janela, trace) e identidade
     * (cliente, vendedor). Ver {@link #HEADER_CLIENTE} para por que a segunda
     * não podia continuar viajando como argumento de tool.
     */
    public record Dados(String janelaChave, String traceId, String clienteRef, String vendedorRef) {

        /** Nenhuma correlação nem identidade — o caso normal de initialize e tools/list. */
        public static final Dados NENHUMA = new Dados(null, null, null, null);

        /** Compat: a forma que existia antes de a identidade entrar. */
        public Dados(String janelaChave, String traceId) {
            this(janelaChave, traceId, null, null);
        }

        public boolean vazio() {
            return branco(janelaChave) && branco(traceId) && branco(clienteRef) && branco(vendedorRef);
        }

        private static boolean branco(String s) {
            return s == null || s.isBlank();
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
        definir(janelaChave, traceId, null, null);
    }

    /** Idem, carregando também a identidade da conversa. */
    public static void definir(String janelaChave, String traceId,
                               String clienteRef, String vendedorRef) {
        Dados d = new Dados(janelaChave, traceId, clienteRef, vendedorRef);
        if (d.vazio()) {
            ATUAL.remove();
            return;
        }
        ATUAL.set(d);
    }

    /** Nunca nulo: ausência é o caso normal (initialize, tools/list, testes). */
    public static Dados atual() {
        Dados d = ATUAL.get();
        return d == null ? Dados.NENHUMA : d;
    }

    public static void limpar() {
        ATUAL.remove();
    }
}
