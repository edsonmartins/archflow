package br.com.archflow.api.agent.stream;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * As execuções que estão rodando agora, por chave de idempotência — o que a rota de cancelamento
 * consulta e o que faz uma chave repetida em andamento receber 409.
 *
 * <p><b>É por réplica.</b> O mapa vive na memória deste processo: o cancelamento de uma chave que
 * caiu noutra réplica não a encontra, e a idempotência em andamento só vale dentro da réplica. Ver
 * a nota de implantação em {@code docs/streaming-invoke.md}.</p>
 */
public final class ExecucoesEmAndamento {

    /** Uma execução registrada. {@code cancelamento} completa quando alguém pede para parar. */
    public static final class Execucao {
        private final String chave;
        private final String tenantId;
        private final CompletableFuture<Void> cancelamento = new CompletableFuture<>();
        private final Thread thread;

        private Execucao(String chave, String tenantId, Thread thread) {
            this.chave = chave;
            this.tenantId = tenantId;
            this.thread = thread;
        }

        public String chave() {
            return chave;
        }

        public String tenantId() {
            return tenantId;
        }

        public CompletableFuture<Void> cancelamento() {
            return cancelamento;
        }
    }

    private final Map<String, Execucao> emAndamento = new ConcurrentHashMap<>();

    /**
     * Registra a execução, que roda em {@code thread}. Vazio quando a chave já está em andamento.
     */
    public synchronized java.util.Optional<Execucao> registrar(String chave, String tenantId,
                                                              Thread thread) {
        if (chave == null || emAndamento.containsKey(chave)) {
            return java.util.Optional.empty();
        }
        Execucao execucao = new Execucao(chave, tenantId, thread);
        emAndamento.put(chave, execucao);
        return java.util.Optional.of(execucao);
    }

    /** A chave está em andamento? */
    public boolean emAndamento(String chave) {
        return chave != null && emAndamento.containsKey(chave);
    }

    /** Quem a registrou, ou {@code null} se não está em andamento. */
    public String tenantDe(String chave) {
        Execucao e = chave == null ? null : emAndamento.get(chave);
        return e == null ? null : e.tenantId;
    }

    /** Alguém já pediu para parar esta chave? */
    public boolean cancelada(String chave) {
        Execucao e = chave == null ? null : emAndamento.get(chave);
        return e != null && e.cancelamento.isDone();
    }

    /**
     * Pede para parar. {@code false} quando a chave não está em andamento aqui.
     *
     * <p>Sinaliza e interrompe a thread <b>sob o mesmo cadeado</b> de {@link #concluir}: a thread
     * de uma execução costuma ser de um pool, e interromper depois de ela ter voltado ao pool
     * cancelaria o trabalho de outra.</p>
     */
    public synchronized boolean cancelar(String chave) {
        Execucao e = chave == null ? null : emAndamento.get(chave);
        if (e == null) {
            return false;
        }
        e.cancelamento.complete(null);
        if (e.thread != null) {
            e.thread.interrupt();
        }
        return true;
    }

    /**
     * Pede para parar <b>esta</b> execução, e só se ainda for a registrada. Uma conexão que fecha
     * depois de a execução ter terminado não pode cancelar uma nova execução da mesma chave.
     */
    public synchronized boolean cancelar(Execucao execucao) {
        return execucao != null && emAndamento.get(execucao.chave) == execucao
                && cancelar(execucao.chave);
    }

    /** A execução terminou. */
    public synchronized void concluir(String chave) {
        if (chave != null) {
            emAndamento.remove(chave);
        }
    }

    public int ativas() {
        return emAndamento.size();
    }
}
