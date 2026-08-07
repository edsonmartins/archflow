package br.com.archflow.langchain4j.provider;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Insere o breakpoint de cache no corpo já montado, decorando o cliente HTTP do
 * langchain4j.
 *
 * <h2>Por que aqui, e não no builder do modelo</h2>
 *
 * <p>O {@code reasoning} é campo de topo e coube em {@code customParameters}. O
 * {@code cache_control} não: ele mora <b>dentro de um bloco de conteúdo</b> da
 * mensagem de sistema, e o adaptador OpenAI do langchain4j serializa o conteúdo
 * de uma {@code SystemMessage} como string simples — não existe ponto de
 * extensão entre o {@code ChatRequest} e o JSON enviado.
 *
 * <p>O cliente HTTP é o ponto de extensão que existe
 * ({@code OpenAiChatModel.builder().httpClientBuilder(...)}), e é onde o corpo
 * ainda pode ser tocado sem forkar o adaptador. A alternativa seria manter uma
 * cópia do serializador do langchain4j, que é muito mais superfície para manter
 * do que uma reescrita de um campo.
 *
 * <p>O caminho nativo da Anthropic <b>não</b> passa por aqui: lá o langchain4j
 * expõe {@code cacheSystemMessages(true)} e é isso que o hub usa.
 *
 * <h2>Instalação condicional</h2>
 *
 * <p>Este decorador só é instalado quando {@code cachePrompt} está ligado
 * <i>e</i> o provedor exige marcação explícita. Nos demais casos o modelo é
 * construído exatamente como antes e o corpo enviado é byte a byte o mesmo —
 * não há decorador no caminho para tocá-lo.
 *
 * @see PromptCacheBreakpoint
 */
final class PromptCacheHttpClient implements HttpClient {

    private final HttpClient delegate;
    private final String modelId;

    /**
     * O aviso de prefixo curto sai <b>uma vez por cliente</b>. O tamanho do
     * prompt de sistema e dos esquemas de tools é praticamente constante ao longo
     * de um laço; repetir o aviso a cada uma das 7 chamadas de uma cotação viraria
     * ruído, e ruído é como um aviso desses deixa de ser lido.
     */
    private final AtomicBoolean primeira = new AtomicBoolean(true);

    PromptCacheHttpClient(HttpClient delegate, String modelId) {
        this.delegate = delegate;
        this.modelId = modelId;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) throws HttpException {
        return delegate.execute(marcar(request));
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser,
                        ServerSentEventListener listener) {
        delegate.execute(marcar(request), parser, listener);
    }

    private HttpRequest marcar(HttpRequest request) {
        String corpo = request.body();
        // getAndSet, e nao um set depois da marcacao: o aviso mais importante e
        // justamente o do caso em que NADA foi marcado, e ele tambem so deve sair
        // uma vez.
        String marcado = PromptCacheBreakpoint.marcar(corpo, modelId, primeira.getAndSet(false));
        if (marcado == corpo) {
            return request;   // nada a marcar: a requisicao segue intacta
        }
        return HttpRequest.builder()
                .method(request.method())
                .url(request.url())
                .headers(request.headers())
                .body(marcado)
                .build();
    }

    /**
     * Builder que embrulha o cliente HTTP padrão do langchain4j (o mesmo que
     * seria usado sem esta funcionalidade), preservando os timeouts que o
     * adaptador configura.
     */
    static HttpClientBuilder builderPara(String modelId) {
        return new Builder(HttpClientBuilderLoader.loadHttpClientBuilder(), modelId);
    }

    private record Builder(HttpClientBuilder delegate, String modelId) implements HttpClientBuilder {

        @Override
        public Duration connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration connectTimeout) {
            delegate.connectTimeout(connectTimeout);
            return this;
        }

        @Override
        public Duration readTimeout() {
            return delegate.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(Duration readTimeout) {
            delegate.readTimeout(readTimeout);
            return this;
        }

        @Override
        public HttpClient build() {
            return new PromptCacheHttpClient(delegate.build(), modelId);
        }
    }
}
