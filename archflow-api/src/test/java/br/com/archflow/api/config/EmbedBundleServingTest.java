package br.com.archflow.api.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * RCHFLW-87: o trecho de Publicar › Incorporar é colado numa página de OUTRA origem e carrega
 * {@code <origem>/archflow-designer.js} com {@code <script type="module">}. Module scripts
 * cross-origin são buscados em modo CORS, então servir o arquivo não basta: sem
 * {@code Access-Control-Allow-Origin} o navegador não o executa. O teste passa pelo resource
 * handler real (o mesmo {@code /**} que serve o SPA), não pelo registry isolado — o que importa é
 * o cabeçalho que sai na resposta do arquivo estático.
 */
@DisplayName("WebMvcConfiguration — bundle do web-component para embed")
class EmbedBundleServingTest {

    private static final String EMBEDDER = "https://parceiro.example.com";

    @TempDir
    Path staticDir;

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    /** Local do estático da vez; o contexto é montado por teste, depois de o @TempDir existir. */
    private static String location;

    @EnableWebMvc
    @Configuration
    static class MvcOn {
        @Bean
        WebMvcConfiguration webMvcConfiguration() {
            return new WebMvcConfiguration(new String[] {EMBEDDER}, new String[] {location});
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(staticDir.resolve("archflow-designer.js"), "export const x = 1;");
        Files.writeString(staticDir.resolve("archflow-designer.css"), ".react-flow{}");
        Files.writeString(staticDir.resolve("index.html"), "<!doctype html><div id=root></div>");

        location = staticDir.toUri().toString();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MvcOn.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("archflow-designer.js sai com Access-Control-Allow-Origin para a origem do embed")
    void bundleJsLiberadoParaOrigemPermitida() throws Exception {
        MvcResult res = mvc.perform(get("/archflow-designer.js").header("Origin", EMBEDDER)).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getContentAsString()).isEqualTo("export const x = 1;");
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(EMBEDDER);
        // Código público buscado sem cookies: não há por que anunciar credenciais.
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    @DisplayName("archflow-designer.css também é liberado")
    void bundleCssLiberado() throws Exception {
        MvcResult res = mvc.perform(get("/archflow-designer.css").header("Origin", EMBEDDER)).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(EMBEDDER);
    }

    @Test
    @DisplayName("origem fora de archflow.cors.allowed-origins não recebe o bundle")
    void origemNaoPermitidaRecusada() throws Exception {
        MvcResult res = mvc.perform(get("/archflow-designer.js").header("Origin", "https://outro.example"))
                .andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(403);
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("o resto do SPA continua sem CORS (a liberação é só do bundle)")
    void spaSemCors() throws Exception {
        MvcResult res = mvc.perform(get("/index.html").header("Origin", EMBEDDER)).andReturn();

        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(res.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
