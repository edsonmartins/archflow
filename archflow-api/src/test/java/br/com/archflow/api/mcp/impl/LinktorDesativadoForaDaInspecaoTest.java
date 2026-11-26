package br.com.archflow.api.mcp.impl;

import br.com.archflow.langchain4j.mcp.McpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-194, item 8: com a integração desativada em /admin/linktor, o Linktor aparecia em Servidores MCP
 * ({@code 2 SERVERS: linktor, self}) e o Inspect respondia OFFLINE, contra o texto da página dele ("Quando
 * ativada, aparece em Servidores MCP"). O seletor do editor já o escondia. A disponibilidade é lida a cada
 * pedido: ligar ou desligar não exige restart.
 */
@DisplayName("Inspeção MCP: o Linktor desativado não aparece nem é inspecionado")
class LinktorDesativadoForaDaInspecaoTest {

    @Test
    void segueALigacaoDaIntegracao() {
        AtomicBoolean ligado = new AtomicBoolean(false);
        AtomicInteger clientesCriados = new AtomicInteger();
        Supplier<McpClient> linktor = () -> {
            clientesCriados.incrementAndGet();
            throw new IllegalStateException("Linktor integration is disabled or mcpCommand is empty");
        };
        var inspecao = new McpInspectionControllerImpl(
                Map.of("linktor", linktor, "self", () -> { throw new IllegalStateException("sem self"); }),
                Map.of(), () -> null, Map.of("linktor", ligado::get));

        assertThat(inspecao.listServerNames()).containsExactly("self");
        assertThat(inspecao.introspect("linktor").connected()).isFalse();
        assertThat(clientesCriados).as("desativado, nem tenta conectar").hasValue(0);

        ligado.set(true);
        assertThat(inspecao.listServerNames()).contains("linktor", "self");
    }
}
