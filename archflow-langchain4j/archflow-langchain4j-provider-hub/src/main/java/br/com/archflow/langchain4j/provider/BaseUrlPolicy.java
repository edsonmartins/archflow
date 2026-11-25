package br.com.archflow.langchain4j.provider;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Para onde uma chave governada pode ir.
 *
 * <p>A chave vem do resolver (tenant ou plataforma); o host de destino vinha do JSON do nó
 * ({@code base.url}, {@code baseUrl}, {@code endpoint}) sem validação nenhuma. Quem edita um fluxo
 * apontava o nó para um servidor seu, executava, e recebia a chave da plataforma — compartilhada
 * entre tenants — num header {@code Authorization}. "Segredo não mora no workflow" de nada vale se o
 * workflow escolhe para quem o segredo é mandado.
 *
 * <p>Regra: com chave governada, o destino tem de ser o endereço oficial do provider
 * ({@link LLMProvider#getBaseUrl()}) ou um dos endereços que o operador listou em
 * {@code archflow.llm.allowed-base-urls} (o {@code archflow.llm.base-url} entra sozinho). Uma chave
 * que o próprio nó trouxe inline não passa por aqui — é do autor, e o destino também.
 *
 * <p>Comparação por esquema + host + porta; o caminho não conta (mesmo host, mesmo dono). Uma
 * entrada da lista sem esquema ({@code gateway.acme.com}) vale só para {@code https}: quem quer
 * mandar chave por {@code http} escreve a URL inteira, e assume isso por escrito.
 */
public final class BaseUrlPolicy {

    private final List<Destino> permitidos;

    private BaseUrlPolicy(List<Destino> permitidos) {
        this.permitidos = List.copyOf(permitidos);
    }

    /** Só os endereços oficiais dos providers. */
    public static BaseUrlPolicy officialOnly() {
        return new BaseUrlPolicy(List.of());
    }

    /**
     * Oficiais mais os endereços do operador.
     *
     * @param allowedBaseUrls URLs ({@code https://gw.acme.com/v1}) ou hosts ({@code gw.acme.com});
     *                        nulos e brancos são ignorados
     * @throws IllegalArgumentException se uma entrada não for URL nem host — melhor falhar na subida
     *                                  do que descobrir na primeira execução que a lista não vale
     */
    public static BaseUrlPolicy of(Collection<String> allowedBaseUrls) {
        List<Destino> lista = new ArrayList<>();
        if (allowedBaseUrls != null) {
            for (String entrada : allowedBaseUrls) {
                if (entrada != null && !entrada.isBlank()) {
                    lista.add(Destino.daEntrada(entrada.trim()));
                }
            }
        }
        return new BaseUrlPolicy(lista);
    }

    /** O destino pode receber uma chave governada do provider? */
    public boolean isAllowed(String provider, String url) {
        Optional<URI> alvo = parse(url);
        if (alvo.isEmpty()) {
            return false;
        }
        URI uri = alvo.get();
        Optional<Destino> oficial = LLMProvider.fromId(provider).map(p -> Destino.oficial(p.getBaseUrl()));
        if (oficial.isPresent() && oficial.get().aceita(uri)) {
            return true;
        }
        return permitidos.stream().anyMatch(d -> d.aceita(uri));
    }

    /**
     * Falha antes de qualquer requisição quando o destino não pode receber a chave.
     *
     * @param campo de onde veio a URL (ex.: {@code base.url}) — entra na mensagem
     */
    public void check(String provider, String url, String campo) {
        if (!isAllowed(provider, url)) {
            throw new IllegalStateException(message(provider, url, campo));
        }
    }

    /** A mensagem de recusa — também usada por quem precisa lançar outra exceção. */
    public static String message(String provider, String url, String campo) {
        String host = parse(url).map(URI::getHost).orElse(String.valueOf(url));
        String oficial = LLMProvider.fromId(provider).map(LLMProvider::getBaseUrl).orElse(null);
        return "[LLM-HOST] O nó aponta '" + campo + "' para '" + host + "', mas a chave de API do provider '"
                + provider + "' é da governança (tenant/plataforma) e só vai para "
                + (oficial != null ? "o endereço oficial (" + oficial + ") ou " : "")
                + "os endereços de archflow.llm.allowed-base-urls. Nenhuma requisição foi feita. "
                + "Remova o '" + campo + "' do nó ou peça ao operador para incluir o host na lista.";
    }

    private static Optional<URI> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(url.trim());
            return uri.getScheme() == null || uri.getHost() == null ? Optional.empty() : Optional.of(uri);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static int portaDe(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    /** Um destino aceito: esquema, padrão de host e porta. */
    private record Destino(String esquema, Pattern host, int porta) {

        boolean aceita(URI uri) {
            return esquema.equalsIgnoreCase(uri.getScheme())
                    && host.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches()
                    && porta == portaDe(uri);
        }

        /**
         * O endereço oficial pode ter partes variáveis ({@code https://{resource}.openai.azure.com}):
         * cada {@code {x}} vale um rótulo de host, nunca um ponto — {@code evil.com.openai.azure.com}
         * não passa por {@code {resource}}.
         */
        static Destino oficial(String baseUrl) {
            String semVariaveis = baseUrl.replaceAll("\\{[^}]+}", "VARIAVEL");
            URI uri = URI.create(semVariaveis);
            String regex = Pattern.quote(uri.getHost().toLowerCase(Locale.ROOT))
                    .replace("variavel", "\\E[a-z0-9-]+\\Q");
            return new Destino(uri.getScheme(), Pattern.compile(regex), portaDe(uri));
        }

        static Destino daEntrada(String entrada) {
            Optional<URI> uri = parse(entrada);
            if (uri.isPresent()) {
                URI u = uri.get();
                return new Destino(u.getScheme(),
                        Pattern.compile(Pattern.quote(u.getHost().toLowerCase(Locale.ROOT))), portaDe(u));
            }
            if (!entrada.matches("[A-Za-z0-9.-]+(:\\d+)?")) {
                throw new IllegalArgumentException("archflow.llm.allowed-base-urls: entrada inválida '"
                        + entrada + "' — use uma URL (https://host/caminho) ou um host");
            }
            String[] partes = entrada.split(":");
            int porta = partes.length > 1 ? Integer.parseInt(partes[1]) : 443;
            return new Destino("https", Pattern.compile(Pattern.quote(partes[0].toLowerCase(Locale.ROOT))),
                    porta);
        }
    }
}
