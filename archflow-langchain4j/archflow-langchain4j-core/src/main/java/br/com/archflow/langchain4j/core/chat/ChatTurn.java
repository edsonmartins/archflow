package br.com.archflow.langchain4j.core.chat;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        return response.aiMessage().text();
    }
}
