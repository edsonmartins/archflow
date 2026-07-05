package br.com.archflow.api.web.assist;

import br.com.archflow.api.assist.AssistService;
import br.com.archflow.api.assist.AssistUnavailableException;
import br.com.archflow.api.assist.dto.AssistErrorResponse;
import br.com.archflow.api.assist.dto.ExplainErrorRequest;
import br.com.archflow.api.assist.dto.ExplainErrorResponse;
import br.com.archflow.api.assist.dto.NlToFlowRequest;
import br.com.archflow.api.assist.dto.NlToFlowResponse;
import br.com.archflow.api.assist.dto.SuggestMappingRequest;
import br.com.archflow.api.assist.dto.SuggestMappingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints síncronos de assistência por IA — família {@code /archflow/assist/*}
 * (ADR-0004). Primeira operação: explicar/diagnosticar erro de execução.
 *
 * <p>Segurança: o caminho é whitelistado no {@code JwtAuthenticationFilter}.
 * Opcionalmente, se {@code archflow.assist.api-key} estiver configurado, exige
 * o header {@code X-ArchFlow-Key} correspondente (senão 401). Se vazio/ausente,
 * libera (modo dev).
 */
@RestController
@RequestMapping("/archflow/assist")
public class SpringAssistController {

    private static final Logger log = LoggerFactory.getLogger(SpringAssistController.class);

    private final AssistService assistService;
    private final String apiKey;

    public SpringAssistController(AssistService assistService,
                                  @Value("${archflow.assist.api-key:}") String apiKey) {
        this.assistService = assistService;
        this.apiKey = apiKey;
    }

    @PostMapping("/explain-error")
    public ResponseEntity<?> explainError(
            @RequestHeader(value = "X-ArchFlow-Key", required = false) String providedKey,
            @RequestBody ExplainErrorRequest request) {

        if (!keyOk(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AssistErrorResponse("NAO_AUTORIZADO",
                            "Header X-ArchFlow-Key ausente ou inválido"));
        }

        ExplainErrorResponse response = assistService.explainError(request);
        return ResponseEntity.ok(response);
    }

    /** #23 — sugestão preditiva de mapeamento de campos (origem→destino). */
    @PostMapping("/suggest-mapping")
    public ResponseEntity<?> suggestMapping(
            @RequestHeader(value = "X-ArchFlow-Key", required = false) String providedKey,
            @RequestBody SuggestMappingRequest request) {

        if (!keyOk(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AssistErrorResponse("NAO_AUTORIZADO",
                            "Header X-ArchFlow-Key ausente ou inválido"));
        }

        SuggestMappingResponse response = assistService.suggestMapping(request);
        return ResponseEntity.ok(response);
    }

    /** #22 — geração de rascunho de workflow a partir de linguagem natural (restrito ao catálogo). */
    @PostMapping("/nl-to-flow")
    public ResponseEntity<?> nlToFlow(
            @RequestHeader(value = "X-ArchFlow-Key", required = false) String providedKey,
            @RequestBody NlToFlowRequest request) {

        if (!keyOk(providedKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AssistErrorResponse("NAO_AUTORIZADO",
                            "Header X-ArchFlow-Key ausente ou inválido"));
        }

        NlToFlowResponse response = assistService.nlToFlow(request);
        return ResponseEntity.ok(response);
    }

    /** Verificação leve de chave estática. Libera quando nenhuma chave está configurada. */
    private boolean keyOk(String providedKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return true;
        }
        return apiKey.equals(providedKey);
    }

    @ExceptionHandler(AssistUnavailableException.class)
    public ResponseEntity<AssistErrorResponse> handleUnavailable(AssistUnavailableException ex) {
        log.warn("Assist indisponível: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(AssistErrorResponse.iaIndisponivel(ex.getMessage()));
    }
}
