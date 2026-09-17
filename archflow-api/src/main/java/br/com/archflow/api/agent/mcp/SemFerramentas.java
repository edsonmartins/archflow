package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Um "servidor" sem ferramenta nenhuma, para o agente que só redige.
 *
 * <p>Existe para quando tudo o que o agente pode dizer já está na entrada. Filtrar as tools do
 * servidor real até o conjunto vazio daria o mesmo catálogo ao modelo, mas ainda faria a ida ao
 * servidor para listá-las — e o agente passaria a depender de um servidor que ele não usa. Aqui não
 * há rede: a lista é vazia e qualquer chamada falha, o que também tira do modelo a tentação de
 * consultar dado que não lhe cabe.</p>
 */
public final class SemFerramentas implements McpClient {

    public static final SemFerramentas INSTANCIA = new SemFerramentas();

    private SemFerramentas() {
    }

    @Override
    public void connect() {
    }

    @Override
    public CompletableFuture<McpModel.ServerInfo> initialize() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void initialized() {
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void close() {
    }

    @Override
    public McpModel.ServerMetadata getServerMetadata() {
        return new McpModel.ServerMetadata("sem-ferramentas");
    }

    @Override
    public McpModel.ServerCapabilities getServerCapabilities() {
        return McpModel.ServerCapabilities.toolsOnly();
    }

    @Override
    public CompletableFuture<List<McpModel.Tool>> listTools() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "este agente não tem ferramentas: " + arguments.name()));
    }
}
