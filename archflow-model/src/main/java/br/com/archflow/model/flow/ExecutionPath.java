package br.com.archflow.model.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Ramo de execução de um fluxo (caminhos paralelos e a árvore que a orquestração
 * dinâmica materializa).
 *
 * <p>{@code @NoArgsConstructor}/{@code @AllArgsConstructor} não são decoração:
 * com apenas {@code @Data @Builder}, o Lombok gera <b>somente</b> um construtor
 * package-private de todos os argumentos. A classe serializava para JSON (pelos
 * getters) e <b>não voltava</b> — sem construtor sem-args acessível, Jackson não
 * consegue instanciá-la. O estado durável gravava os ramos e os lia de volta como
 * {@code null}, em silêncio.
 *
 * <p>Preferidos a {@code @Jacksonized} de propósito: {@code archflow-model} é o
 * modelo de domínio e não depende de Jackson — amarrá-lo a um framework de
 * serialização para resolver isto seria pior que o problema.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPath {
    private String pathId;
    private PathStatus status;
    private List<String> completedSteps;
    private List<ExecutionPath> parallelBranches;
}
