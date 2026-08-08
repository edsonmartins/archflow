package br.com.archflow.api.flow;

import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.ErrorType;
import br.com.archflow.model.flow.StepError;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.metrics.StepMetrics;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Minimal {@link StepResult} for the linear workflow runner (design-0004 step 1). */
public final class SimpleStepResult implements StepResult {

    private final String stepId;
    private final StepStatus status;
    private final Object output;
    private final long elapsedMs;
    private final List<StepError> errors;

    private SimpleStepResult(String stepId, StepStatus status, Object output, long elapsedMs, List<StepError> errors) {
        this.stepId = stepId;
        this.status = status;
        this.output = output;
        this.elapsedMs = elapsedMs;
        this.errors = errors;
    }

    public static SimpleStepResult ok(String stepId, Object output, long elapsedMs) {
        return new SimpleStepResult(stepId, StepStatus.COMPLETED, output, elapsedMs, List.of());
    }

    /**
     * Falha do passo, com o motivo em <b>ambos</b> os lugares onde alguém o procura.
     *
     * <h2>O que motivou</h2>
     *
     * <p>Esta função guardava a mensagem só em {@code output} e devolvia
     * {@code errors = List.of()}, com um comentário dizendo que era de propósito
     * ("the failure message is carried in getOutput()"). Do outro lado,
     * {@code DefaultFlowExecutor.handleFailure} lê {@code getErrors()} e cai no
     * {@code orElse("Sem mensagem de erro")}. Os dois lados eram internamente
     * coerentes e <b>discordavam entre si</b>: um escrevia em {@code output}, o
     * outro lia {@code errors}.
     *
     * <p>Medido em 07–08/08: <b>toda</b> falha chegou ao Core como
     * {@code "Fluxo falhou: sem erro registrado"} — quatro causas distintas
     * (400 do Bedrock por prompt caching, saída por tool ausente,
     * {@code IOException} sem cabeçalho, {@code ConnectException}) com o mesmo
     * texto inútil. Isso apesar de a correção do salto seguinte já estar em
     * produção: ela lê {@code step.<id>.error} do contexto, e o que
     * {@code handleFailure} grava lá é justamente a lista vazia.
     *
     * <p>O custo é tempo de diagnóstico. Cada falha desta semana exigiu abrir o
     * log do container por SSH para descobrir uma causa que o próprio ArchFlow já
     * conhecia. E, do lado do produto, é o que o vendedor pode saber: com "sem
     * erro registrado" não há o que dizer além de "não consegui".
     *
     * <h2>Por que na origem, e não no leitor</h2>
     *
     * <p>Dava para fazer {@code handleFailure} cair para {@code getOutput()}
     * quando {@code getErrors()} está vazio. Isso conserta um leitor e deixa o
     * contrato ambíguo — o próximo a ler {@code getErrors()} volta a encontrar
     * lista vazia numa falha com mensagem. Construindo o {@link StepError} aqui,
     * <b>qualquer</b> leitor passa a funcionar.
     *
     * <p>{@code output} continua carregando a mensagem: quem já lia de lá não
     * quebra.
     *
     * @param message motivo da falha; quando ausente, {@code errors} fica vazio —
     *                inventar texto é pior que não ter, porque some a distinção
     *                entre "falhou sem dizer" e "falhou dizendo isto"
     */
    public static SimpleStepResult failed(String stepId, String message, long elapsedMs) {
        List<StepError> errors = (message == null || message.isBlank())
                ? List.of()
                : List.of(StepError.of(ErrorType.EXECUTION, "STEP_FAILED", message));
        return new SimpleStepResult(stepId, StepStatus.FAILED, message, elapsedMs, errors);
    }

    @Override public String getStepId() { return stepId; }
    @Override public StepStatus getStatus() { return status; }
    @Override public Optional<Object> getOutput() { return Optional.ofNullable(output); }
    @Override public StepMetrics getMetrics() { return new StepMetrics(elapsedMs, 0, 0, Map.of()); }
    @Override public List<StepError> getErrors() { return errors; }
}
