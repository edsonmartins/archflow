package br.com.archflow.api.agent.mcp;

import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Uma imagem que quem acionou o agente manda junto do texto — a foto de um encarte, o print de uma
 * conversa, a página de um PDF já rasterizada.
 *
 * <p>Entra no primeiro turno do usuário, ao lado do texto, como {@code ImageContent} da LangChain4j.
 * O modelo precisa ter visão; quem escolhe o modelo do nó é quem escreve o fluxo.</p>
 *
 * <p>Só os tipos que os provedores de visão aceitam em comum; qualquer outro é recusado aqui, antes
 * de gastar uma chamada que o provedor recusaria com uma mensagem pior.</p>
 *
 * @param mime   {@code image/png}, {@code image/jpeg}, {@code image/webp} ou {@code image/gif}
 * @param base64 o conteúdo em base64, sem o prefixo {@code data:}
 */
public record ImagemDaEntrada(String mime, String base64) {

    static final Set<String> TIPOS = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    public ImagemDaEntrada {
        Objects.requireNonNull(mime, "mime é obrigatório");
        Objects.requireNonNull(base64, "base64 é obrigatório");
        mime = mime.trim().toLowerCase(Locale.ROOT);
        if (!TIPOS.contains(mime)) {
            throw new IllegalArgumentException("imagem de tipo não aceito: " + mime + " (aceitos: " + TIPOS + ")");
        }
        if (base64.isBlank()) {
            throw new IllegalArgumentException("imagem vazia");
        }
        try {
            Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("imagem com base64 inválido", e);
        }
    }
}
