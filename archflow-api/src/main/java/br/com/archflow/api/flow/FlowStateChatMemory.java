package br.com.archflow.api.flow;

import br.com.archflow.engine.core.MemoryRestorer;
import br.com.archflow.langchain4j.core.memory.ChatMessageCodec;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Faz a memória de chat sobreviver à suspensão de um fluxo.
 *
 * <p>Um fluxo com nó de chat acumula a conversa em
 * {@link ExecutionContext#getChatMemory()} — o adapter faz {@code memory.add}
 * da pergunta e da resposta. Só que essa memória vive no heap: ao retomar, o
 * endpoint de resume construía uma {@code MessageWindowChatMemory} vazia e a
 * conversa inteira sumia. Um gate de aprovação humana no meio de um diálogo
 * devolvia o agente amnésico.
 *
 * <p>As mensagens são serializadas para dentro das <b>variáveis do FlowState</b>,
 * que já são persistidas a cada checkpoint. Não se cria tabela nova: o estado
 * durável do fluxo já é o lugar onde tudo que precisa sobreviver ao restart
 * mora, e uma segunda fonte de verdade para memória seria o mesmo erro que dois
 * motores de durabilidade.
 *
 * <p>O tamanho é limitado pela janela da própria {@code ChatMemory} (o
 * {@code MessageWindowChatMemory} descarta o excedente), então a variável não
 * cresce sem limite.
 *
 * <p>Usa o {@link ChatMessageCodec} canônico — o mesmo dos adapters de memória
 * Redis/JDBC. Um formato próprio aqui divergiria deles com o tempo.
 */
public final class FlowStateChatMemory implements MemoryRestorer {

    private static final Logger log = LoggerFactory.getLogger(FlowStateChatMemory.class);

    /** Variável de contexto com a conversa serializada (uma string JSON por mensagem). */
    public static final String CHAT_MEMORY_KEY = "__archflow.chatMemory";

    /**
     * Copia a memória de chat corrente para as variáveis do contexto, de onde o
     * checkpoint a leva ao estado durável. Silencioso quando não há memória —
     * a maioria dos fluxos não tem nó de chat.
     */
    public static void capture(ExecutionContext context) {
        if (context == null) {
            return;
        }
        ChatMemory memory = context.getChatMemory();
        if (memory == null) {
            return;
        }
        try {
            List<ChatMessage> messages = memory.messages();
            if (messages == null || messages.isEmpty()) {
                return;
            }
            List<String> encoded = new ArrayList<>(messages.size());
            for (ChatMessage message : messages) {
                encoded.add(ChatMessageCodec.toJson(message));
            }
            context.set(CHAT_MEMORY_KEY, encoded);
        } catch (RuntimeException e) {
            // Perder o histórico é ruim; derrubar o fluxo por causa dele é pior.
            log.warn("Falha ao capturar a memória de chat do fluxo: {}", e.getMessage());
        }
    }

    @Override
    public void restore(ExecutionContext context) {
        if (context == null || context.getChatMemory() == null) {
            return;
        }
        Object stored = context.get(CHAT_MEMORY_KEY).orElse(null);
        if (!(stored instanceof Collection<?> encoded) || encoded.isEmpty()) {
            return;
        }
        ChatMemory memory = context.getChatMemory();
        int restored = 0;
        int discarded = 0;
        for (Object entry : encoded) {
            ChatMessage message = null;
            try {
                // O codec devolve null (não lança) para texto que não é JSON
                // canônico de mensagem — inserir esse null na memória plantaria
                // um buraco que só explodiria na próxima chamada ao modelo.
                message = ChatMessageCodec.fromJson(String.valueOf(entry));
            } catch (RuntimeException e) {
                log.debug("Mensagem de chat ilegível: {}", e.getMessage());
            }
            if (message == null) {
                discarded++;
                continue;
            }
            memory.add(message);
            restored++;
        }
        if (discarded > 0) {
            // Uma mensagem ilegível não pode custar as demais, mas some em
            // silêncio seria pior — o histórico volta incompleto.
            log.warn("{} mensagem(ns) de chat descartada(s) na retomada por formato inválido",
                    discarded);
        }
        log.info("Memória de chat restaurada: {} mensagem(ns)", restored);
    }
}
