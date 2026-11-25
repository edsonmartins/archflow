package br.com.archflow.agent.orchestration;

import br.com.archflow.orchestration.Goal;
import br.com.archflow.orchestration.PlanSpec;
import br.com.archflow.orchestration.Planner;
import br.com.archflow.orchestration.Usage;

import java.util.List;

/**
 * {@link Planner} que diz quanto a decomposição gastou, para o {@link DynamicSupervisor} debitar no
 * orçamento.
 *
 * <p>O planejador chama o LLM a cada rodada, e o {@code Planner} do núcleo devolve só a lista: o uso que
 * o provedor informava se perdia e o ledger ficava em zero — o campo "Orçamento (tokens)" não tinha
 * efeito nenhum (RCHFLW-62).</p>
 */
public interface MeteredPlanner extends Planner<String> {

    /**
     * As subtarefas e o uso da chamada.
     *
     * @param usage o que o provedor informou, ou {@code null} quando não informou — e então o gasto é
     *              desconhecido, não zero
     */
    record Decomposition(List<String> items, Usage usage) {

        public Decomposition {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    Decomposition decomposeMetered(Goal goal, PlanSpec spec);

    @Override
    default List<String> decompose(Goal goal, PlanSpec spec) {
        return decomposeMetered(goal, spec).items();
    }
}
