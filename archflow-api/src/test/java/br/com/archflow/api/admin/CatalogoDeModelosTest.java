package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.GlobalConfigDto.LLMModelDto;
import br.com.archflow.api.admin.dto.GlobalConfigDto.ToggleModelRequest;
import br.com.archflow.api.admin.dto.TenantDto;
import br.com.archflow.api.admin.dto.TenantDto.CreateTenantRequest;
import br.com.archflow.api.admin.dto.TenantDto.TenantLimitsDto;
import br.com.archflow.api.admin.impl.GlobalConfigControllerImpl;
import br.com.archflow.api.admin.impl.TenantControllerImpl;
import br.com.archflow.api.admin.store.InMemoryGlobalConfigStore;
import br.com.archflow.api.workflow.dto.ProviderDto;
import br.com.archflow.api.workflow.impl.WorkflowConfigControllerImpl;
import br.com.archflow.langchain4j.provider.LLMProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catálogo de modelos (RCHFLW-79). Eram três listas que não conversavam: o seletor do nó (enum
 * fixo), o catálogo global do superadmin (ids de exemplo fora do formato do OpenRouter, que nada
 * consumia) e os permitidos por tenant (sem campo na criação, nada aplicava). Agora o catálogo é um só
 * — {@code ARCHFLOW_LLM_MODELS} ou a lista do código — e os outros dois são recortes dele.
 */
@DisplayName("Catálogo de modelos: env → catálogo global → tenant → seletor do nó")
class CatalogoDeModelosTest {

    private static final String ENV =
            "openai/gpt-6-luna, google/gemma-4-31b-it:free,,nvidia/nemotron-3-ultra-550b-a55b:free,openai/gpt-6-luna";

    @Nested
    @DisplayName("catálogo global (GlobalConfigControllerImpl)")
    class CatalogoGlobal {

        @Test
        @DisplayName("ARCHFLOW_LLM_MODELS semeia o catálogo: os ids exatos, do provider da plataforma")
        void envSemeiaOCatalogo() {
            var global = new GlobalConfigControllerImpl("openrouter", ENV);

            assertThat(global.getModels()).extracting(LLMModelDto::id).containsExactly(
                    "openai/gpt-6-luna", "google/gemma-4-31b-it:free", "nvidia/nemotron-3-ultra-550b-a55b:free");
            assertThat(global.getModels()).allSatisfy(m -> {
                assertThat(m.provider()).isEqualTo("openrouter");
                assertThat(m.status()).isEqualTo("active");
                assertThat(m.costInputPer1M()).as("custo desconhecido é nulo, não zero").isNull();
            });
        }

        @Test
        @DisplayName("sem a variável, vale a lista do código — com ids do OpenRouter no formato dele")
        void semEnvValeAListaDoCodigo() {
            var global = new GlobalConfigControllerImpl("openrouter", "  ");

            List<String> openrouter = global.getModels().stream()
                    .filter(m -> "openrouter".equals(m.provider())).map(LLMModelDto::id).toList();
            assertThat(openrouter).isNotEmpty().allMatch(id -> id.contains("/"));
            assertThat(openrouter).contains("anthropic/claude-sonnet-4")
                    .doesNotContain("anthropic/claude-sonnet-4-20250514");
            assertThat(global.getModels()).extracting(LLMModelDto::provider).contains("openai", "anthropic");
        }

        @Test
        @DisplayName("o superadmin ainda desliga: o status gravado vale por cima da semente, e sai dos ativos")
        void superadminDesliga() {
            var store = new InMemoryGlobalConfigStore();
            new GlobalConfigControllerImpl(store, "openrouter", ENV)
                    .toggleModel("google/gemma-4-31b-it:free", new ToggleModelRequest(false));

            var depois = new GlobalConfigControllerImpl(store, "openrouter", ENV);

            assertThat(depois.getModels()).filteredOn(m -> m.id().equals("google/gemma-4-31b-it:free"))
                    .singleElement().satisfies(m -> assertThat(m.status()).isEqualTo("deprecated"));
            assertThat(depois.activeModels()).extracting(LLMModelDto::id)
                    .containsExactly("openai/gpt-6-luna", "nvidia/nemotron-3-ultra-550b-a55b:free");
        }

        @Test
        @DisplayName("os ids vêm da variável: um catálogo gravado antes (ids de exemplo) não ressuscita")
        void catalogoAntigoNaoRessuscita() throws Exception {
            var store = new InMemoryGlobalConfigStore();
            store.put("models", new ObjectMapper().writeValueAsString(List.of(
                    new LLMModelDto("gpt-4o", "GPT-4o", "OpenAI", "active", 2.5, 10.0),
                    new LLMModelDto("openai/gpt-6-luna", "x", "openrouter", "deprecated", null, null))));

            var global = new GlobalConfigControllerImpl(store, "openrouter", "openai/gpt-6-luna,novo/modelo");

            assertThat(global.getModels()).extracting(LLMModelDto::id)
                    .containsExactly("openai/gpt-6-luna", "novo/modelo");
            assertThat(global.activeModels()).extracting(LLMModelDto::id)
                    .as("o desligado continua desligado; o novo da variável aparece sem limpar o banco")
                    .containsExactly("novo/modelo");
        }
    }

    @Nested
    @DisplayName("permitidos do tenant (TenantControllerImpl)")
    class PermitidosDoTenant {

        private static CreateTenantRequest criar(String id, List<String> allowed, TenantLimitsDto limits) {
            return new CreateTenantRequest(id, id, "a@b.c", "Other", "professional", null, limits, allowed);
        }

        @Test
        @DisplayName("lista vazia ou tenant sem cadastro = sem restrição (vazio, nunca 'nada')")
        void vazioESemRestricao() {
            var tenants = new TenantControllerImpl(false);
            tenants.createTenant(criar("acme", List.of(), null));

            assertThat(tenants.allowedModels("acme")).isEmpty();
            assertThat(tenants.allowedModels("desconhecido")).isEmpty();
            assertThat(tenants.allowedModels(null)).isEmpty();
        }

        @Test
        @DisplayName("criação grava a lista normalizada (aparada, sem vazios nem repetidos)")
        void criacaoNormaliza() {
            var tenants = new TenantControllerImpl(false);
            tenants.createTenant(criar("acme", List.of(" openai/gpt-6-luna ", "", "openai/gpt-6-luna", "b"), null));

            assertThat(tenants.allowedModels("acme")).hasValue(List.of("openai/gpt-6-luna", "b"));
        }

        @Test
        @DisplayName("cliente que só mandou limits.allowedModels não perde a restrição")
        void limitsComoFallback() {
            var tenants = new TenantControllerImpl(false);
            tenants.createTenant(criar("acme", null,
                    new TenantLimitsDto(1, 1, 1, 1, List.of("m1"), List.of())));

            assertThat(tenants.allowedModels("acme")).hasValue(List.of("m1"));
        }

        @Test
        @DisplayName("edição troca a lista; lista vazia remove a restrição; ausente mantém")
        void edicao() {
            var tenants = new TenantControllerImpl(false);
            tenants.createTenant(criar("acme", List.of("m1"), null));

            tenants.updateTenant("acme", parcial(null));
            assertThat(tenants.allowedModels("acme")).hasValue(List.of("m1"));

            tenants.updateTenant("acme", parcial(List.of("m2", " m3")));
            assertThat(tenants.allowedModels("acme")).hasValue(List.of("m2", "m3"));

            tenants.updateTenant("acme", parcial(List.of()));
            assertThat(tenants.allowedModels("acme")).isEmpty();
        }

        private static TenantDto parcial(List<String> allowed) {
            return new TenantDto(null, null, null, null, null, null, null, null, allowed, null);
        }
    }

    @Nested
    @DisplayName("seletor do nó (GET /api/workflow/providers)")
    class Seletor {

        private static WorkflowConfigControllerImpl seletor(GlobalConfigControllerImpl global,
                                                           Optional<List<String>> permitidos) {
            var controller = new WorkflowConfigControllerImpl();
            controller.setModelCatalog(global::activeModels, () -> permitidos, "openrouter");
            return controller;
        }

        private static ProviderDto provider(List<ProviderDto> providers, String id) {
            return providers.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        }

        @Test
        @DisplayName("lista o catálogo ativo (não o enum), e o provider da plataforma vem marcado")
        void listaOCatalogoAtivo() {
            var store = new InMemoryGlobalConfigStore();
            var global = new GlobalConfigControllerImpl(store, "openrouter", ENV);
            global.toggleModel("nvidia/nemotron-3-ultra-550b-a55b:free", new ToggleModelRequest(false));

            List<ProviderDto> providers = seletor(global, Optional.empty()).listProviders();

            ProviderDto openrouter = provider(providers, "openrouter");
            assertThat(openrouter.models()).extracting(ProviderDto.ModelDto::id)
                    .containsExactly("openai/gpt-6-luna", "google/gemma-4-31b-it:free");
            assertThat(openrouter.platformDefault()).isTrue();
            assertThat(openrouter.allowCustomModel()).as("tenant sem restrição digita um id livre").isTrue();
            assertThat(provider(providers, "openai").models())
                    .as("o catálogo da variável é do provider da plataforma; o enum não entra")
                    .isEmpty();
            assertThat(provider(providers, "openai").platformDefault()).isFalse();
        }

        @Test
        @DisplayName("o modelo da plataforma vai no provider da plataforma, para o painel saber o que se herda")
        void modeloDaPlataformaNoProviderDela() {
            var global = new GlobalConfigControllerImpl(new InMemoryGlobalConfigStore(), "openrouter", ENV);
            var controller = new WorkflowConfigControllerImpl();
            controller.setModelCatalog(global::activeModels, Optional::empty, "openrouter", "openai/gpt-6-luna");

            List<ProviderDto> providers = controller.listProviders();

            assertThat(provider(providers, "openrouter").defaultModel()).isEqualTo("openai/gpt-6-luna");
            assertThat(provider(providers, "openai").defaultModel())
                    .as("ninguém herda o modelo da plataforma num provider que não é o dela")
                    .isNull();
        }

        @Test
        @DisplayName("id que o código não conhece vai com janela 0 (desconhecida), não com número inventado")
        void idDesconhecidoSemNumeroInventado() {
            var global = new GlobalConfigControllerImpl("openrouter", "openai/gpt-6-luna,openai/gpt-4o");

            ProviderDto openrouter = provider(seletor(global, Optional.empty()).listProviders(), "openrouter");

            assertThat(openrouter.models()).filteredOn(m -> m.id().equals("openai/gpt-6-luna"))
                    .singleElement().satisfies(m -> assertThat(m.contextWindow()).isZero());
            assertThat(openrouter.models()).filteredOn(m -> m.id().equals("openai/gpt-4o"))
                    .singleElement().satisfies(m -> assertThat(m.contextWindow()).isEqualTo(
                            LLMProvider.OPENROUTER.getModel("openai/gpt-4o").orElseThrow().contextWindow()));
        }

        @Test
        @DisplayName("tenant restrito: catálogo ∩ permitidos, e sem id livre")
        void tenantRestrito() {
            var global = new GlobalConfigControllerImpl("openrouter", ENV);

            List<ProviderDto> providers = seletor(global,
                    Optional.of(List.of("google/gemma-4-31b-it:free", "fora/do-catalogo"))).listProviders();

            assertThat(provider(providers, "openrouter").models()).extracting(ProviderDto.ModelDto::id)
                    .containsExactly("google/gemma-4-31b-it:free");
            assertThat(providers).allSatisfy(p -> assertThat(p.allowCustomModel()).isFalse());
        }

        @Test
        @DisplayName("lista de permitidos vazia é 'sem restrição'")
        void listaVaziaSemRestricao() {
            var global = new GlobalConfigControllerImpl("openrouter", ENV);

            ProviderDto openrouter = provider(seletor(global, Optional.of(List.of())).listProviders(), "openrouter");

            assertThat(openrouter.models()).hasSize(3);
            assertThat(openrouter.allowCustomModel()).isTrue();
        }

        @Test
        @DisplayName("sem catálogo ligado (host mínimo), continua o enum com id livre")
        void semCatalogoContinuaOEnum() {
            ProviderDto openai = provider(new WorkflowConfigControllerImpl().listProviders(), "openai");

            assertThat(openai.models()).extracting(ProviderDto.ModelDto::id).contains("gpt-4o");
            assertThat(openai.allowCustomModel()).isTrue();
        }
    }
}
