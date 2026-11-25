package br.com.archflow.langchain4j.core.chat;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ModelUsageListener;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Uma volta de chat dos adapters ({@code generate} / {@code chat}), com o prompt de sistema do nó.
 *
 * <p>Existe porque os três adapters de chat (OpenAI, Anthropic, OpenRouter) repetiam a mesma
 * chamada e nenhum lia o {@code systemPrompt} que o painel do nó grava (a persona também escreve
 * ali): o prompt era descartado em silêncio e o modelo respondia sem as instruções (RCHFLW-52).
 * Concentrar aqui é o que impede um adapter novo de nascer com o mesmo esquecimento.
 *
 * <p><b>O prompt de sistema não entra na {@link ChatMemory}.</b> A memória da execução é uma janela
 * de mensagens que também é capturada no checkpoint; guardá-lo lá o faria ser expulso da janela numa
 * conversa longa, e um nó seguinte (com outro prompt) herdaria as instruções deste. Ele é posto na
 * frente da lista a cada chamada.
 */
public final class ChatTurn {

    /** Propriedade do nó com as instruções de sistema — o painel e a persona gravam aqui. */
    public static final String SYSTEM_PROMPT = "systemPrompt";

    private ChatTurn() {
    }

    /** O prompt de sistema das propriedades do adapter, ou {@code null} quando ausente ou em branco. */
    public static String systemPromptOf(Map<String, Object> properties) {
        if (properties == null) {
            return null;
        }
        return properties.get(SYSTEM_PROMPT) instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * {@code generate}: uma volta sem memória. Sem prompt de sistema a chamada é a mesma de antes
     * ({@code model.chat(String)}), para não mudar nada em quem não configurou um.
     */
    public static String generate(ChatModel model, String systemPrompt, String input) {
        if (systemPrompt == null) {
            return model.chat(input);
        }
        ChatResponse response = model.chat(List.of(SystemMessage.from(systemPrompt), UserMessage.from(input)));
        return response.aiMessage().text();
    }

    /**
     * {@code generate} relatando o consumo ao {@link ModelUsageListener} do contexto (RCHFLW-75). Sem
     * ouvinte é exatamente o {@link #generate(ChatModel, String, String)}; com ele, a volta sem prompt
     * vai como lista de uma {@link UserMessage} — a mesma requisição de {@code chat(String)}, mas com a
     * resposta inteira, de onde sai o uso.
     *
     * @param provider  o provedor do adapter ({@code openai}…)
     * @param modelName o modelo pedido, sem o provedor
     */
    public static String generate(ChatModel model, String systemPrompt, String input,
                                  ExecutionContext context, String provider, String modelName) {
        Optional<ModelUsageListener> listener = ModelUsageListener.from(context);
        if (listener.isEmpty()) {
            return generate(model, systemPrompt, input);
        }
        List<ChatMessage> messages = systemPrompt == null ? List.of(UserMessage.from(input))
                : List.of(SystemMessage.from(systemPrompt), UserMessage.from(input));
        ChatResponse response = model.chat(messages);
        report(listener.get(), provider, modelName, response);
        return response.aiMessage().text();
    }

    /** {@code chat} relatando o consumo ao {@link ModelUsageListener} do contexto, se houver. */
    public static String chat(ChatModel model, String systemPrompt, ChatMemory memory, String input,
                              ExecutionContext context, String provider, String modelName) {
        Optional<ModelUsageListener> listener = ModelUsageListener.from(context);
        ChatResponse response = chatResponse(model, systemPrompt, memory, input);
        listener.ifPresent(l -> report(l, provider, modelName, response));
        return response.aiMessage().text();
    }

    /**
     * Relata o uso da resposta. O rótulo é {@code provider/modelo} pedido, a mesma chave do laço de
     * agente e da tabela de preços. Resposta sem uso conta o turno com tokens nulos — "não informado",
     * não zero.
     */
    private static void report(ModelUsageListener listener, String provider, String modelName, ChatResponse response) {
        TokenUsage usage = response.tokenUsage();
        String label = modelName == null || modelName.isBlank() ? null
                : provider == null || provider.isBlank() ? modelName : provider + "/" + modelName;
        listener.onModelCall(provider, label,
                usage == null || usage.inputTokenCount() == null ? null : usage.inputTokenCount().longValue(),
                usage == null || usage.outputTokenCount() == null ? null : usage.outputTokenCount().longValue());
    }

    /**
     * {@code chat}: a volta com a memória da execução.
     *
     * <p>A mensagem do usuário e a resposta só entram na memória <b>depois</b> que o modelo
     * respondeu. Antes, o usuário era gravado primeiro: uma chamada que falhava deixava a pergunta
     * órfã na janela, e o fallback do OpenRouter a gravava de novo — o modelo de reserva via a mesma
     * pergunta duas vezes.
     *
     * <p>Com prompt de sistema no nó, uma {@link SystemMessage} que esteja na memória (de outra
     * origem) fica de fora desta chamada: as instruções do nó são as que valem para ele, e duas
     * mensagens de sistema contraditórias deixariam o modelo escolher.
     *
     * <p>A memória é a da execução inteira — dois nós com {@code operation: chat} em ramos paralelos
     * usam a mesma janela, e a {@code MessageWindowChatMemory} não é thread-safe. A leitura e o par
     * pergunta/resposta são feitos sob o monitor da memória (a chamada ao modelo não), então a janela
     * não perde mensagem nem intercala a pergunta de um nó com a resposta do outro.
     */
    public static String chat(ChatModel model, String systemPrompt, ChatMemory memory, String input) {
        return chatResponse(model, systemPrompt, memory, input).aiMessage().text();
    }

    private static ChatResponse chatResponse(ChatModel model, String systemPrompt, ChatMemory memory, String input) {
        UserMessage user = UserMessage.from(input);
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        List<ChatMessage> history;
        synchronized (memory) {
            history = List.copyOf(memory.messages());
        }
        for (ChatMessage message : history) {
            if (systemPrompt == null || !(message instanceof SystemMessage)) {
                messages.add(message);
            }
        }
        messages.add(user);
        ChatResponse response = model.chat(messages);
        synchronized (memory) {
            memory.add(user);
            memory.add(response.aiMessage());
        }
        return response;
    }
}
