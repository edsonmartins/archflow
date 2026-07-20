package br.com.archflow.langchain4j.core.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;

/**
 * Codec único de (de)serialização de {@link ChatMessage} para os backends de
 * memória (Redis, JDBC, ...). Delega à serialização canônica da LangChain4j
 * ({@link ChatMessageSerializer}/{@link ChatMessageDeserializer}), que cobre
 * <b>todos</b> os tipos de mensagem (user, system, ai com tool requests,
 * tool execution result, e evolui com a lib) — em vez de cada adapter manter
 * um formato de fio próprio (que já havia nascido divergente entre Redis e
 * JDBC: um usava {@code type:"tool_execution_result"}/{@code content}, o outro
 * {@code role:"tool"}/{@code text}).
 *
 * <p>{@link #fromJson} tolera o formato canônico; formatos legados
 * específicos de cada adapter (texto puro role-based) ficam a cargo do
 * fallback do próprio adapter quando {@code fromJson} devolve {@code null}.
 */
public final class ChatMessageCodec {

    private ChatMessageCodec() {
    }

    /** Serializa no formato canônico da LangChain4j. */
    public static String toJson(ChatMessage message) {
        return ChatMessageSerializer.messageToJson(message);
    }

    /**
     * Desserializa do formato canônico. Retorna {@code null} (sem lançar) se o
     * texto não for JSON canônico de mensagem — o adapter então aplica o seu
     * fallback de formato legado.
     */
    public static ChatMessage fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return ChatMessageDeserializer.messageFromJson(json);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
