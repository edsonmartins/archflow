package br.com.archflow.api.web.admin;

import br.com.archflow.api.admin.dto.GlobalConfigDto.LLMModelDto;
import br.com.archflow.api.admin.impl.GlobalConfigControllerImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O superadmin desliga um modelo do catálogo cujo id tem barra (RCHFLW-79). Os ids do OpenRouter são
 * {@code fornecedor/modelo}, e com {@code /models/{id}} a rota dava 404: com {@code ARCHFLOW_LLM_MODELS}
 * de OpenRouter, nenhum modelo do catálogo podia ser desligado.
 */
@DisplayName("PUT /api/admin/global/models/{id} com id do OpenRouter (com barra)")
class ToggleDeModeloComBarraTest {

    @Test
    @DisplayName("desliga openai/gpt-6-luna e google/gemma-4-31b-it:free; id sem barra continua funcionando")
    void desligaIdComBarra() throws Exception {
        var global = new GlobalConfigControllerImpl("openrouter", "openai/gpt-6-luna,google/gemma-4-31b-it:free,x");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SpringGlobalConfigController(global)).build();

        mvc.perform(put("/api/admin/global/models/openai/gpt-6-luna")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/global/models/google/gemma-4-31b-it%3Afree")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/admin/global/models/x")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\": false}"))
                .andExpect(status().isOk());

        assertThat(global.activeModels()).extracting(LLMModelDto::id).isEmpty();
    }
}
