package br.com.archflow.api.jobs;

/** Cooperative progress and cancellation channel exposed to handlers. */
public interface JobContext {

    String jobId();

    /**
     * O tenant do registro do job — o da identidade que o submeteu ({@code JobService.submit}). É o
     * único tenant em que o handler pode ler e gravar: um {@code tenantId} no payload vem do cliente
     * (o {@code POST /api/jobs} aceita qualquer payload) e, lido como verdade, deixava um usuário do
     * tenant B reescrever o documento da base de conhecimento do tenant A (QA2 P5).
     */
    String tenantId();

    void progress(int percentage, String message);

    boolean isCancellationRequested();

    default void checkCancelled() {
        if (isCancellationRequested()) {
            throw new JobCancelledException();
        }
    }
}
