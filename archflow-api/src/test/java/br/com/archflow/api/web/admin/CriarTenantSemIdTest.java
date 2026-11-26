package br.com.archflow.api.web.admin;

import br.com.archflow.api.admin.impl.TenantControllerImpl;
import br.com.archflow.api.config.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RCHFLW-193: {@code POST /api/admin/tenants} sem {@code tenantId} respondia {@code 500 Internal Server
 * Error} — o id nulo virava chave de um {@code ConcurrentHashMap} e estourava NPE. Corpo incompleto é
 * erro de quem chama: {@code 400} com a frase do que faltou, e nada gravado.
 */
@DisplayName("Criar tenant sem id: 400 legível, não 500")
class CriarTenantSemIdTest {

    private TenantControllerImpl tenants;
    private MockMvc mvc;

    @BeforeEach
    void monta() {
        tenants = new TenantControllerImpl(false);
        mvc = MockMvcBuilders.standaloneSetup(new SpringTenantController(tenants))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest(name = "corpo {0}")
    @ValueSource(strings = {"{}", "{\"id\":\"../x\",\"name\":\"X\"}", "{\"tenantId\":\"  \",\"name\":\"X\"}"})
    void semTenantIdRecusado(String corpo) throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo))
                .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("tenantId is required");
        assertThat(tenants.listTenants()).isEmpty();
    }
}
