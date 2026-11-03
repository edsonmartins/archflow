package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.ImagemDaEntrada;
import br.com.archflow.api.agent.mcp.McpAgentComponent;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.ToolAccessPolicy;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * As imagens que o Core manda no invoke chegam ao modelo, no primeiro turno do usuário.
 *
 * <p>O caso que motivou: a cobertura de preço do concorrente no VendaX lê a foto de um encarte. O OCR
 * do doclenz liga 23 de 33 preços ao produto; um modelo com visão lendo a imagem, 33 de 33. Sem
 * imagem, nada muda: o primeiro turno é a mesma mensagem de texto de sempre.</p>
 */
@DisplayName("imagem no invoke")
class ImagemNoInvokeTest {

    private static final String PNG = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    private static final List<ImagemDaEntrada> IMAGENS = List.of(new ImagemDaEntrada("image/png", PNG));

    private static final ResolvedLLMConfig CONFIG = ResolvedLLMConfig.builder()
            .provider("openrouter").model("google/gemini-2.5-flash-lite").build();

    private static final class ModeloQueGuarda implements ChatModel {
        final List<List<ChatMessage>> pedidos = new ArrayList<>();

        @Override public ChatResponse chat(ChatRequest request) {
            pedidos.add(List.copyOf(request.messages()));
            return ChatResponse.builder().aiMessage(AiMessage.from("{\"itens\":[]}")).build();
        }
    }

    private static final class SemTools implements McpClient {
        @Override public void connect() { }
        @Override public CompletableFuture<McpModel.ServerInfo> initialize() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void initialized() { }
        @Override public boolean isConnected() { return true; }
        @Override public void close() { }
        @Override public McpModel.ServerMetadata getServerMetadata() {
            return new McpModel.ServerMetadata("fake");
        }
        @Override public McpModel.ServerCapabilities getServerCapabilities() {
            return McpModel.ServerCapabilities.toolsOnly();
        }
        @Override public CompletableFuture<List<McpModel.Tool>> listTools() {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
            return CompletableFuture.completedFuture(McpModel.ToolResult.text("{}"));
        }
    }

    private static UserMessage primeiroTurno(List<ImagemDaEntrada> imagens) {
        ModeloQueGuarda modelo = new ModeloQueGuarda();
        LLMConfigResolver resolver = new LLMConfigResolver() {
            @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return CONFIG; }
            @Override public ChatModel resolveModel(LLMResolutionRequest r) { return modelo; }
        };
        new McpAgentRunner(resolver, CONFIG).run("acme", "extraia os itens", "o encarte do concorrente",
                new SemTools(), new McpAgentRunner.Options(ToolAccessPolicy.allowAll()).comImagens(imagens));
        return (UserMessage) modelo.pedidos.get(0).get(1);
    }

    @Test
    @DisplayName("com imagem, o primeiro turno leva o texto e depois a imagem, com o tipo")
    void comImagem() {
        UserMessage turno = primeiroTurno(IMAGENS);

        assertThat(turno.contents()).hasSize(2);
        assertThat(((TextContent) turno.contents().get(0)).text()).isEqualTo("o encarte do concorrente");
        ImageContent imagem = (ImageContent) turno.contents().get(1);
        assertThat(imagem.image().base64Data()).isEqualTo(PNG);
        assertThat(imagem.image().mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("sem imagem, o primeiro turno é o texto puro de sempre")
    void semImagem() {
        UserMessage turno = primeiroTurno(List.of());

        assertThat(turno.hasSingleText()).isTrue();
        assertThat(turno.singleText()).isEqualTo("o encarte do concorrente");
    }

    @Test
    @DisplayName("o turno com imagem atravessa o codec do estado suspenso")
    void codec() {
        UserMessage turno = primeiroTurno(IMAGENS);

        ChatMessage volta = ChatMessageDeserializer.messageFromJson(ChatMessageSerializer.messageToJson(turno));

        assertThat(volta).isEqualTo(turno);
    }

    @Test
    @DisplayName("o envelope desserializa as imagens; ausentes, lista vazia")
    void envelope() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        VendaxInvoke com = mapper.readValue("""
                {"tenantId":"t1","conversationId":"c1","agent":"CPE",
                 "imagens":[{"mime":"image/PNG","base64":"%s"},null]}""".formatted(PNG), VendaxInvoke.class);
        VendaxInvoke sem = mapper.readValue("""
                {"tenantId":"t1","conversationId":"c1","agent":"CPE"}""", VendaxInvoke.class);

        assertThat(com.imagens()).containsExactly(new ImagemDaEntrada("image/png", PNG));
        assertThat(sem.imagens()).isEmpty();
    }

    @Test
    @DisplayName("tipo que não é imagem, ou base64 inválido, é recusado antes de chamar o modelo")
    void recusa() {
        assertThatThrownBy(() -> new ImagemDaEntrada("application/pdf", PNG))
                .hasMessageContaining("tipo não aceito");
        assertThatThrownBy(() -> new ImagemDaEntrada("image/png", "isto não é base64!"))
                .hasMessageContaining("base64 inválido");
    }

    @Test
    @DisplayName("fluxo: as imagens vão no contexto, sob chave que não é persistida")
    void fluxo() {
        ExecutionContext contexto = executarFluxo(invoke(IMAGENS));

        assertThat(McpAgentComponent.CTX_IMAGENS).startsWith(ExecutionKeys.TRANSIENT_PREFIX);
        assertThat(contexto.get(McpAgentComponent.CTX_IMAGENS)).contains(IMAGENS);
        assertThat(ExecutionKeys.persistiveis(contexto.getVariables()))
                .as("a imagem do cliente não vai para o estado durável do fluxo")
                .doesNotContainKey(McpAgentComponent.CTX_IMAGENS);
    }

    @Test
    @DisplayName("fluxo sem imagens: nenhuma chave")
    void fluxoSemImagens() {
        assertThat(executarFluxo(invoke(List.of())).get(McpAgentComponent.CTX_IMAGENS)).isEmpty();
    }

    private static VendaxInvoke invoke(List<ImagemDaEntrada> imagens) {
        return new VendaxInvoke("1.0", "t1", "c1", "CPE", "m1", "o encarte", "LIGHT", "extracao",
                "trace", null, null, null, "chave", null, List.of(), null, imagens, null);
    }

    private ExecutionContext executarFluxo(VendaxInvoke invoke) {
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        FlowEngine engine = mock(FlowEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        when(engine.execute(any(), any())).thenReturn(CompletableFuture.completedFuture(new Concluido()));

        new AgentFlowRunner(deserializer, provider, mock(FlowRepository.class), null)
                .executar(invoke, Map.of("steps", List.of()), "entrada");

        ArgumentCaptor<ExecutionContext> ctx = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(engine).execute(any(), ctx.capture());
        return ctx.getValue();
    }

    private static class Concluido implements FlowResult {
        @Override public Optional<Object> getOutput() { return Optional.of("{}"); }
        @Override public ExecutionStatus getStatus() { return ExecutionStatus.COMPLETED; }
        @Override public List<br.com.archflow.model.error.ExecutionError> getErrors() { return List.of(); }
        @Override public br.com.archflow.model.engine.ExecutionMetrics getMetrics() { return null; }
    }
}
