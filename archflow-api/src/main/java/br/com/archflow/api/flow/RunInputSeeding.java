package br.com.archflow.api.flow;

import br.com.archflow.model.engine.ExecutionContext;

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
 */
public final class RunInputSeeding {

    public static final String INPUT_KEY = "input";

    private RunInputSeeding() {
    }

    public static void seedVariables(ExecutionContext ctx, Map<String, Object> variables) {
        if (ctx == null || variables == null) {
            return;
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
