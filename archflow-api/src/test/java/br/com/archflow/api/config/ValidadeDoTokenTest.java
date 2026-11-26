package br.com.archflow.api.config;

import br.com.archflow.security.jwt.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-192: {@code ARCHFLOW_JWT_TTL} só mudava o {@code expiresIn} da resposta do login. O bean
 * {@code jwtService} usava o construtor sem validade, então o token expirava sempre em 900 s — com
 * {@code ARCHFLOW_JWT_TTL=3600} o login dizia 3600 e o cliente via o token morrer em 15 minutos.
 */
@DisplayName("Validade do access token: a do ARCHFLOW_JWT_TTL, a mesma do expiresIn")
class ValidadeDoTokenTest {

    private static final String SECRET = "test-secret-key-256-bits-long-enough-for-hs256-ok";

    private final ArchflowBeanConfiguration config = new ArchflowBeanConfiguration();

    private static long validadeEmSegundos(JwtService jwt, String token) {
        long iat = jwt.extractClaim(token, c -> c.getIssuedAt().toInstant().getEpochSecond());
        long exp = jwt.extractClaim(token, c -> c.getExpiration().toInstant().getEpochSecond());
        return exp - iat;
    }

    @Test
    @DisplayName("ttl de 3600 s: exp - iat = 3600 (antes 900)")
    void ttlConfiguradoValeNoToken() {
        JwtService jwt = config.jwtService(SECRET, 3600);

        String token = jwt.generateTenantAccessToken("u-1", "ana", "tenant_a", new String[] {"VIEWER"});

        assertThat(validadeEmSegundos(jwt, token)).isEqualTo(3600);
        assertThat(jwt.getAccessTokenExpirationMs()).isEqualTo(3_600_000L);
    }

    @Test
    @DisplayName("o refresh token segue com os 7 dias de sempre")
    void refreshInalterado() {
        JwtService jwt = config.jwtService(SECRET, 3600);

        String refresh = jwt.generateRefreshToken("u-1", "ana");

        assertThat(validadeEmSegundos(jwt, refresh))
                .isEqualTo(JwtService.DEFAULT_REFRESH_TOKEN_EXPIRATION / 1000);
    }

    @Test
    @DisplayName("o bean do token e o expiresIn do login leem a mesma propriedade, com o mesmo padrão")
    void mesmaPropriedadeNosDoisLugares() throws Exception {
        Method jwtBean = Arrays.stream(ArchflowBeanConfiguration.class.getMethods())
                .filter(m -> m.getName().equals("jwtService")).findFirst().orElseThrow();
        Method authBean = Arrays.stream(ArchflowBeanConfiguration.class.getMethods())
                .filter(m -> m.getName().equals("authControllerImpl")).findFirst().orElseThrow();

        assertThat(valorDoTtl(jwtBean)).isNotNull().isEqualTo(valorDoTtl(authBean));
    }

    private static String valorDoTtl(Method bean) {
        for (Parameter p : bean.getParameters()) {
            Value v = p.getAnnotation(Value.class);
            if (v != null && v.value().contains("access-token-expiration-seconds")) {
                return v.value();
            }
        }
        return null;
    }
}
