package br.com.archflow.api.flow;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Como a entrada de uma execução vira variáveis do contexto — a mesma regra para o REST
 * ({@code POST /api/workflows/{id}/execute}) e para o AG-UI ({@code POST /ag-ui/workflows/{id}}).
 *
 * <p>Cada chave do objeto vira uma variável. Além disso, se o objeto não traz uma chave
 * {@code input}, o objeto inteiro fica em {@code input}: é por ela que os nós leem "a entrada do
 * fluxo" ({@code decision} julga {@code ${input}} por padrão). Antes só o AG-UI fazia isso, então a
 * mesma entrada {@code {"message": "meu boleto venceu"}} dava AUTO/pagamentos pelo Executar do editor
 * e ESCALATE pelo Executar da lista ou pelo "Executar novamente" — a decisão não via texto nenhum.</p>
 *
 * <p>Um {@code input} explícito vence: {@code {"input": "texto"}} continua chegando como o texto, não
 * como o mapa que o contém.</p>
 *
 * <p>As chaves do motor ({@link ExecutionKeys#reservadaAoMotor}) são descartadas: quem dispara a
 * execução não pode semear a decisão de um portão, o histórico de quem aprovou nem o checkpoint de
 * passos concluídos — qualquer um dos três deixava o chamador passar pelo portão sem pedido na fila.</p>
 */
public final class RunInputSeeding {

    public static final String INPUT_KEY = "input";

    private static final Logger log = LoggerFactory.getLogger(RunInputSeeding.class);

    private RunInputSeeding() {
    }

    public static void seedVariables(ExecutionContext ctx, Map<String, Object> entrada) {
        if (ctx == null || entrada == null) {
            return;
        }
        Map<String, Object> variables = ExecutionKeys.semChavesDoMotor(entrada);
        if (variables.size() != entrada.size()) {
            // Só os nomes: o valor é o que o chamador tentou forjar e não precisa ir para o log.
            log.warn("Entrada da execução trazia chaves reservadas ao motor; ignoradas: {}",
                    entrada.keySet().stream().filter(ExecutionKeys::reservadaAoMotor).toList());
        }
        variables.forEach(ctx::set);
        // Objeto vazio é "sem entrada", não uma entrada "{}": é o corpo padrão de um cliente MCP sem argumentos e
        // do SDK (execute(id, null)). Virar input faria o ORCHESTRATE sem goal rodar com o objetivo "{}" em vez de
        // falhar pedindo um, e o mcp-agent mandar o literal "{}" ao modelo.
        if (!variables.isEmpty() && !variables.containsKey(INPUT_KEY)) {
            ctx.set(INPUT_KEY, variables);
        }
    }
}
