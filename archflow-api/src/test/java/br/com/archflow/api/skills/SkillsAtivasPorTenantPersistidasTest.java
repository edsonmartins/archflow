package br.com.archflow.api.skills;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.skills.dto.SkillDto;
import br.com.archflow.api.skills.impl.SkillsControllerImpl;
import br.com.archflow.api.skills.store.JdbcSkillActivationStore;
import br.com.archflow.langchain4j.skills.Skill;
import br.com.archflow.langchain4j.skills.SkillsManager;
import jakarta.servlet.http.HttpServletRequest;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-66: o switch de Admin > Skills vivia só em memória e voltava a inativo em todo restart. Aqui o
 * estado passa pelo store JDBC sobre a migration real (V6_14) num H2 em modo PostgreSQL, e o "restart" é
 * um controller e um store novos sobre o mesmo banco.
 */
@DisplayName("Skills ativas — por tenant, persistidas")
class SkillsAtivasPorTenantPersistidasTest {

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:skills-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        migrate("V6_14__create_tenant_active_skills.sql");
    }

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void migrate(String file) throws Exception {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/" + file)) {
            assertThat(in).as(file).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder semComentarios = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.strip().startsWith("--")) {
                semComentarios.append(line).append('\n');
            }
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String statement : semComentarios.toString().split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
        }
    }

    /** Um processo novo: catálogo recarregado do "diretório" e store novo sobre o mesmo banco. */
    private SkillsControllerImpl subirServidor() {
        SkillsManager manager = new SkillsManager();
        manager.register(new Skill("docx", "Word docs", "use docx", List.of()));
        manager.register(new Skill("pdf", "PDF docs", "use pdf", List.of()));
        return new SkillsControllerImpl(manager, new JdbcSkillActivationStore(dataSource));
    }

    private void tenant(String tenantId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(ImpersonationFilter.ATTR_TENANT_ID)).thenReturn(tenantId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    @DisplayName("a skill ligada continua ligada depois do restart, só para o tenant que a ligou")
    void sobreviveAoRestart() {
        tenant("tenant_a");
        subirServidor().activate("docx");

        SkillsControllerImpl depois = subirServidor();

        tenant("tenant_a");
        assertThat(depois.listSkills()).extracting(SkillDto::name, SkillDto::active)
                .containsExactlyInAnyOrder(tuple("docx", true), tuple("pdf", false));
        assertThat(depois.listActiveSkills()).extracting(SkillDto::name).containsExactly("docx");

        tenant("tenant_b");
        assertThat(depois.listActiveSkills()).isEmpty();
        assertThat(depois.listSkills()).extracting(SkillDto::active).containsOnly(false);
    }

    @Test
    @DisplayName("desligar também persiste, e só no tenant da requisição")
    void desligarPersiste() {
        SkillsControllerImpl antes = subirServidor();
        tenant("tenant_a");
        antes.activate("docx");
        antes.activate("pdf");
        tenant("tenant_b");
        antes.activate("pdf");

        tenant("tenant_a");
        antes.deactivate("pdf");

        SkillsControllerImpl depois = subirServidor();
        tenant("tenant_a");
        assertThat(depois.listActiveSkills()).extracting(SkillDto::name).containsExactly("docx");
        tenant("tenant_b");
        assertThat(depois.listActiveSkills()).extracting(SkillDto::name).containsExactly("pdf");
    }

    @Test
    @DisplayName("ativar duas vezes e desativar o que não está ativo são idempotentes")
    void idempotente() {
        SkillsControllerImpl controller = subirServidor();
        tenant("tenant_a");

        controller.activate("docx");
        controller.activate("docx");
        controller.deactivate("pdf");

        assertThat(new JdbcSkillActivationStore(dataSource).activeSkills("tenant_a")).containsExactly("docx");
    }
}
