package br.com.archflow.api.agent.stream;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.vendax.VendaxAgentDispatcher;
import br.com.archflow.api.agent.vendax.VendaxInvoke;
import br.com.archflow.api.agent.vendax.VendaxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A invocação com resposta em fluxo: aceita, executa e entrega o resultado exatamente uma vez.
 *
 * <h2>Entrega</h2>
 * <ul>
 *   <li>{@code fim} escrito na conexão: não há webhook.</li>
 *   <li>Conexão encerrada antes do {@code fim} (queda, cancelamento, {@code erro}): o resultado
 *       vai pelo webhook, com a mesma chave.</li>
 * </ul>
 *
 * <p>Nada aqui conhece um agente ou uma aplicação: o fluxo vem na definição, e o que o motor
 * produz é entregue como veio.</p>
 */
public final class InvocacaoEmFluxo implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InvocacaoEmFluxo.class);

    /** O que a admissão decidiu. */
    public sealed interface Admissao {
        /** Aceita: devolver o emitter como resposta. */
        record Aceita(SseEmitter emitter) implements Admissao {
        }

        /** Recusada: {@code retryAfterSegundos} só no 429. */
        record Recusa(int status, String motivo, Integer retryAfterSegundos) implements Admissao {
        }
    }

    private final VendaxAgentDispatcher dispatcher;
    private final ExecucoesEmAndamento execucoes;
    private final ResultadoGuardado guardado;
    private final ScheduledExecutorService relogio;
    private final int maxConcorrentes;
    private final Duration batimento;
    private final int retryAfterSegundos;
    private final java.util.function.Supplier<SseEmitter> fabrica;
    private final AtomicInteger ativas = new AtomicInteger();

    public InvocacaoEmFluxo(VendaxAgentDispatcher dispatcher, ExecucoesEmAndamento execucoes,
                            ResultadoGuardado guardado, int maxConcorrentes,
                            Duration batimento, int retryAfterSegundos) {
        this(dispatcher, execucoes, guardado, maxConcorrentes, batimento, retryAfterSegundos,
                () -> new SseEmitter(0L));
    }

    /** Com a fábrica do emitter à mão, para o teste observar o que sai na conexão. */
    InvocacaoEmFluxo(VendaxAgentDispatcher dispatcher, ExecucoesEmAndamento execucoes,
                     ResultadoGuardado guardado, int maxConcorrentes, Duration batimento,
                     int retryAfterSegundos, java.util.function.Supplier<SseEmitter> fabrica) {
        this.fabrica = fabrica;
        this.dispatcher = dispatcher;
        this.execucoes = execucoes;
        this.guardado = guardado;
        // Um relógio só, daemon: ele só escreve batimento, e não pode segurar o processo no shutdown.
        var executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1, tarefa -> {
            Thread t = new Thread(tarefa, "batimento-do-fluxo");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        this.relogio = executor;
        this.maxConcorrentes = maxConcorrentes;
        this.batimento = batimento;
        this.retryAfterSegundos = retryAfterSegundos;
    }

    @Override
    public void close() {
        relogio.shutdownNow();
    }

    public int ativas() {
        return ativas.get();
    }

    /** Pede para parar a execução desta chave (em fluxo ou assíncrona). {@code false}: não está aqui. */
    public boolean cancelar(String chave) {
        return execucoes.cancelar(chave);
    }

    public Admissao iniciar(VendaxInvoke invoke) {
        String chave = VendaxResult.idempotencyKeyOf(invoke);

        // TERMINADA: devolve só o fim guardado, sem chamar o modelo. Outro tenant com a mesma chave
        // não pode ler o resultado alheio.
        var guardadoDaChave = guardado.buscar(chave);
        if (guardadoDaChave.isPresent()) {
            VendaxResult anterior = guardadoDaChave.get();
            if (!java.util.Objects.equals(anterior.tenantId(), invoke.tenantId())) {
                return new Admissao.Recusa(409, "idempotencyKey em uso por outra execução", null);
            }
            return new Admissao.Aceita(reenviarFim(anterior));
        }

        if (ativas.incrementAndGet() > maxConcorrentes) {
            ativas.decrementAndGet();
            return new Admissao.Recusa(429, "limite de execuções em fluxo simultâneas atingido",
                    retryAfterSegundos);
        }

        SseEmitter emitter = fabrica.get();
        boolean texto = !temSchema(invoke);
        SessaoSse sessao = new SessaoSse(emitter, texto);
        Thread thread = Thread.ofVirtual().name("invoke-em-fluxo-", 0)
                .unstarted(() -> executar(invoke, chave, sessao));
        var registro = execucoes.registrar(chave, invoke.tenantId(), thread);
        if (registro.isEmpty()) {
            ativas.decrementAndGet();
            return new Admissao.Recusa(409, "idempotencyKey já está em execução", null);
        }
        // Os dois sentidos do cancelamento: quem pede por chave para a sessão, e a conexão que cai
        // pede por chave para a execução (que interrompe a thread).
        registro.get().cancelamento().thenRun(() -> sessao.cancelamento().complete(null));
        sessao.cancelamento().thenRun(() -> execucoes.cancelar(registro.get()));
        thread.start();
        return new Admissao.Aceita(emitter);
    }

    private static boolean temSchema(VendaxInvoke invoke) {
        var def = invoke.definicao();
        return def != null && def.saidaSchema() != null && !def.saidaSchema().isBlank();
    }

    private SseEmitter reenviarFim(VendaxResult resultado) {
        SseEmitter emitter = fabrica.get();
        SessaoSse sessao = new SessaoSse(emitter, false);
        sessao.fim(resultado);
        sessao.encerrar();
        return emitter;
    }

    private void executar(VendaxInvoke invoke, String chave, SessaoSse sessao) {
        ContadorDeUso uso = new ContadorDeUso();
        long passo = Math.max(100, batimento.toMillis() / 3);
        ScheduledFuture<?> tarefa = relogio.scheduleWithFixedDelay(
                () -> sessao.batimento(batimento.toNanos()), passo, passo, TimeUnit.MILLISECONDS);
        VendaxResult resultado;
        boolean recuperavel = false;
        try {
            sessao.inicio(chave, invoke.traceId());
            var feito = dispatcher.executarEmFluxo(invoke, sessao, uso);
            resultado = feito.resultado();
            recuperavel = feito.recuperavel();
            // Cancelado (pelo chamador ou porque a conexão caiu): o que não terminou OK é o
            // cancelamento, com o texto parcial e o consumo até aqui.
            if (sessao.cancelada() && !VendaxResult.OK.equals(resultado.status())) {
                resultado = dispatcher.comUsoDe(
                        VendaxResult.cancelado(invoke, sessao.textoEmitido()), uso);
            }
            // Provedor ou nó final sem streaming: o texto sai num único delta, para os deltas
            // concatenados serem sempre iguais ao texto do resultado.
            if (sessao.transmite() && VendaxResult.OK.equals(resultado.status())
                    && VendaxResult.TEXTO.equals(resultado.richObjectType())
                    && sessao.textoEmitido().isEmpty()) {
                sessao.delta(resultado.richObject());
            }
        } catch (RuntimeException e) {
            log.error("Execução em fluxo falhou (chave={}): {}", chave, e.getMessage(), e);
            resultado = dispatcher.comUsoDe(
                    VendaxResult.error(invoke, "falha interna na execução em fluxo"), uso);
            recuperavel = true;
        }

        // Guardar e liberar a chave ANTES de entregar: o cancelamento interrompe a thread, e uma
        // interrupção durante o envio do webhook o perderia.
        if (VendaxResult.OK.equals(resultado.status())) {
            guardado.guardar(chave, resultado);
        }
        execucoes.concluir(chave);
        Thread.interrupted();
        tarefa.cancel(false);
        try {
            entregar(invoke, sessao, resultado, recuperavel);
        } finally {
            ativas.decrementAndGet();
        }
    }

    private void entregar(VendaxInvoke invoke, SessaoSse sessao, VendaxResult resultado,
                          boolean recuperavel) {
        boolean ehErro = VendaxResult.ERROR.equals(resultado.status())
                && (resultado.encerradoPor() == null
                        || !VendaxResult.CANCELADO.equals(resultado.encerradoPor().name()));
        if (ehErro) {
            // `erro` encerra a conexão antes do fim; o resultado vai pelo webhook.
            sessao.erro(resultado.error(), recuperavel);
        } else if (sessao.fim(resultado)) {
            return;
        }
        try {
            dispatcher.entregarPorWebhook(resultado, invoke);
        } catch (RuntimeException e) {
            log.error("Webhook do resultado em fluxo falhou (chave={}): {}",
                    resultado.idempotencyKey(), e.getMessage(), e);
        }
    }
}
