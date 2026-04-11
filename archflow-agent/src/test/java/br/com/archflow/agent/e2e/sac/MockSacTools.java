package br.com.archflow.agent.e2e.sac;

import br.com.archflow.model.ai.Tool;
import br.com.archflow.model.ai.domain.ParameterDescription;
import br.com.archflow.model.ai.domain.Result;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock SAC tools for E2E testing.
 *
 * <p>Three fake tools that mirror the SAC agent's actual tools:
 * <ul>
 *   <li>{@code tracking_pedido} — track an order by number</li>
 *   <li>{@code consultar_pedidos_cliente} — list customer orders</li>
 *   <li>{@code criar_ticket_reclamacao} — open a complaint ticket</li>
 * </ul>
 *
 * <p>Each tool records its invocations for assertion in the test.
 */
public class MockSacTools {

    private final List<ToolInvocation> invocations = new ArrayList<>();

    public record ToolInvocation(String toolName, Map<String, Object> params, String tenantId) {}

    public List<ToolInvocation> getInvocations() {
        return List.copyOf(invocations);
    }

    public List<ToolInvocation> invocationsOf(String toolName) {
        return invocations.stream().filter(i -> i.toolName.equals(toolName)).toList();
    }

    public void reset() {
        invocations.clear();
    }

    // ── Factory methods ─────────────────────────────────────────────

    public Tool trackingPedido() {
        return new BaseTool("tracking_pedido", "Track an order by number") {
            @Override
            public Result execute(Map<String, Object> params, ExecutionContext context) {
                invocations.add(new ToolInvocation("tracking_pedido", params, context.getTenantId()));
                String numero = String.valueOf(params.get("numero_pedido"));
                return Result.success(Map.of(
                    "numero_pedido", numero,
                    "status", "EM_TRANSITO",
                    "previsao_entrega", "2026-04-15",
                    "transportadora", "Express Cargo"
                ));
            }

            @Override
            public List<ParameterDescription> getParameters() {
                return List.of(
                    new ParameterDescription("numero_pedido", "string", "Número do pedido", true, null, null)
                );
            }
        };
    }

    public Tool consultarPedidosCliente() {
        return new BaseTool("consultar_pedidos_cliente", "List customer orders") {
            @Override
            public Result execute(Map<String, Object> params, ExecutionContext context) {
                invocations.add(new ToolInvocation("consultar_pedidos_cliente", params, context.getTenantId()));
                return Result.success(Map.of(
                    "cnpj", String.valueOf(params.get("cnpj")),
                    "pedidos", List.of(
                        Map.of("numero", "12345", "data", "2026-04-01", "valor", 1500.00),
                        Map.of("numero", "12346", "data", "2026-04-05", "valor", 890.50)
                    )
                ));
            }

            @Override
            public List<ParameterDescription> getParameters() {
                return List.of(
                    new ParameterDescription("cnpj", "string", "CNPJ do cliente", true, null, null)
                );
            }
        };
    }

    public Tool criarTicketReclamacao() {
        return new BaseTool("criar_ticket_reclamacao", "Open a complaint ticket") {
            @Override
            public Result execute(Map<String, Object> params, ExecutionContext context) {
                invocations.add(new ToolInvocation("criar_ticket_reclamacao", params, context.getTenantId()));
                return Result.success(Map.of(
                    "ticket_id", "TKT-" + System.currentTimeMillis(),
                    "status", "ABERTO",
                    "motivo", String.valueOf(params.get("motivo"))
                ));
            }

            @Override
            public List<ParameterDescription> getParameters() {
                return List.of(
                    new ParameterDescription("motivo", "string", "Motivo da reclamação", true, null, null)
                );
            }
        };
    }

    // ── Base implementation reducing boilerplate ───────────────────

    private abstract static class BaseTool implements Tool {
        private final String name;
        private final String description;

        BaseTool(String name, String description) {
            this.name = name;
            this.description = description;
        }

        @Override
        public ComponentMetadata getMetadata() {
            return new ComponentMetadata(
                name, name, description,
                ComponentType.TOOL, "1.0.0",
                Set.of("sac"),
                List.of(),
                new HashMap<>(),
                Set.of("mock")
            );
        }

        @Override
        public void initialize(Map<String, Object> config) { /* no-op */ }

        @Override
        public Object execute(String operation, Object input, ExecutionContext context) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = input instanceof Map ? (Map<String, Object>) input : Map.of();
            return execute(params, context);
        }

        @Override
        public void shutdown() { /* no-op */ }

        @Override
        public void validateParameters(Map<String, Object> params) {
            for (ParameterDescription p : getParameters()) {
                if (p.required() && !params.containsKey(p.name())) {
                    throw new IllegalArgumentException("Missing required parameter: " + p.name());
                }
            }
        }
    }
}
