package br.com.archflow.conversation.service;

import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.persistence.jdbc.JdbcConversationRepository;
import br.com.archflow.conversation.persistence.jdbc.JdbcSuspendedConversationStore;
import br.com.archflow.conversation.state.SuspendedConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-181: com JDBC ligado, a transcrição vai para {@code conversations} e
 * {@code conversation_messages} e sobrevive a um restart. Antes o serviço guardava as mensagens num mapa
 * próprio: as tabelas ficavam com 0 linhas e toda conversa sumia ao reiniciar o processo.
 *
 * <p>O schema é o das migrations reais (V2_1, V2_2), e o "restart" é um serviço novo — manager, stores
 * e repositório novos — sobre o mesmo banco.</p>
 */
@DisplayName("Transcrição durável das conversas (RCHFLW-181)")
class TranscricaoDuravelTest {

    private DataSource dataSource;

    @BeforeEach
    void banco() throws SQLException {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:transcricao-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("RUNSCRIPT FROM 'classpath:db/migration/V2_1__create_conversations.sql'");
            st.execute("RUNSCRIPT FROM 'classpath:db/migration/V2_2__create_suspended_conversations.sql'");
        }
    }

    /** Um processo: o serviço como o servidor o monta com JDBC ligado. */
    private ConversationService processo() {
        return new DefaultConversationService(
                new ConversationManager(Duration.ofMinutes(30), new JdbcSuspendedConversationStore(dataSource)),
                new JdbcConversationRepository(dataSource));
    }

    private int linhas(String tabela) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static FormData formulario() {
        return FormData.builder().id("f").title("Dados")
                .addField(FormData.FormField.text("nome", "Nome").build()).build();
    }

    private static ConversationMessage abertura(String conversationId, String tenant, String texto) {
        return new ConversationMessage(UUID.randomUUID().toString(), conversationId,
                ConversationMessage.Role.USER, texto, java.time.Instant.now(),
                Map.of(DefaultConversationService.OWNER_METADATA, tenant));
    }

    @Test
    @DisplayName("as mensagens vão para o banco e a conversa reabre, com a transcrição, depois do restart")
    void sobreviveAoRestart() throws Exception {
        ConversationService antes = processo();
        ConversationMessage primeira = abertura("conv-1", "tenant-a", "Meu pedido chegou quebrado");
        antes.addMessage(primeira);
        antes.addMessage(ConversationMessage.assistant("conv-1", "Sinto muito! Qual o número do pedido?"));
        antes.addMessage(ConversationMessage.user("conv-1", "4471"));

        assertThat(linhas("conversations")).isEqualTo(1);
        assertThat(linhas("conversation_messages")).isEqualTo(3);

        ConversationService depois = processo();
        List<ConversationMessage> transcricao = depois.getMessages("conv-1");
        assertThat(transcricao).extracting(ConversationMessage::content)
                .containsExactly("Meu pedido chegou quebrado", "Sinto muito! Qual o número do pedido?", "4471");
        assertThat(transcricao).extracting(ConversationMessage::role).containsExactly(
                ConversationMessage.Role.USER, ConversationMessage.Role.ASSISTANT, ConversationMessage.Role.USER);
        assertThat(transcricao.get(0).id()).isEqualTo(primeira.id());
        // O dono é o da mensagem que abriu a conversa — e só a lista dele a traz.
        Conversation registro = depois.getRecord("conv-1").orElseThrow();
        assertThat(registro.tenantId()).isEqualTo("tenant-a");
        assertThat(depois.listConversationIds("tenant-a")).contains("conv-1");
        assertThat(depois.listConversationIds("tenant-b")).doesNotContain("conv-1");
    }

    @Test
    @DisplayName("as falas sem tenant (agente, formulário enviado) vão para o tenant do registro, e o dono de "
            + "uma conversa nascida suspensa é o do contexto da suspensão")
    void donoDaSuspensa() {
        ConversationService antes = processo();
        FormData form = formulario();
        SuspendedConversation suspensa = antes.suspend("conv-2", null, null, form, Duration.ofMinutes(30),
                Map.of(DefaultConversationService.OWNER_METADATA, "tenant-x"));
        antes.resume(suspensa.getResumeToken(), Map.of("nome", "Joana"));

        ConversationService depois = processo();
        assertThat(depois.getRecord("conv-2")).map(Conversation::tenantId).contains("tenant-x");
        assertThat(depois.getMessages("conv-2")).extracting(ConversationMessage::content)
                .containsExactly("Conversa suspensa: aguardando o preenchimento do formulário",
                        "Formulário enviado: nome=Joana");
    }

    @Test
    @DisplayName("cancelar uma conversa ativa, nunca suspensa, a deixa CANCELLED — e isso também sobrevive")
    void cancelaAtiva() {
        ConversationService antes = processo();
        antes.addMessage(abertura("conv-3", "tenant-a", "oi"));

        assertThat(antes.cancel("conv-3")).isTrue();
        assertThat(antes.cancel("nao-existe")).isFalse();

        ConversationService depois = processo();
        assertThat(depois.getRecord("conv-3")).map(Conversation::status)
                .contains(Conversation.ConversationStatus.CANCELLED);
        assertThat(depois.getMessages("conv-3")).extracting(ConversationMessage::content)
                .containsExactly("oi", "Conversa cancelada");
        // Cancelar de novo não repete o aviso.
        assertThat(depois.cancel("conv-3")).isTrue();
        assertThat(depois.getMessages("conv-3")).hasSize(2);
    }

    @Test
    @DisplayName("a criação do registro é a da primeira mensagem, não a da suspensão")
    void criacaoEADaPrimeiraMensagem() throws Exception {
        ConversationService servico = processo();
        ConversationMessage primeira = abertura("conv-4", "tenant-a", "oi");
        servico.addMessage(primeira);
        Thread.sleep(5);
        servico.suspend("conv-4", null, null, formulario(), Duration.ofMinutes(30), Map.of());

        // Ao milissegundo: a coluna TIMESTAMP guarda microssegundos, o Instant pode trazer nanos.
        assertThat(processo().getRecord("conv-4").orElseThrow().createdAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(primeira.timestamp().truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("retirar o formulário só vale para uma conversa que ainda o espera")
    void retiraSoAQueEspera() {
        ConversationService servico = processo();
        FormData form = formulario();
        SuspendedConversation suspensa = servico.suspend("conv-5", null, null, form, Duration.ofMinutes(30),
                Map.of());

        assertThat(servico.withdrawForm("conv-5")).map(SuspendedConversation::getResumeToken)
                .contains(suspensa.getResumeToken());
        assertThat(servico.getById("conv-5")).isEmpty();
        assertThat(servico.withdrawForm("conv-5")).isEmpty();
        assertThat(servico.getByToken(suspensa.getResumeToken())).isEmpty();
    }

    @Test
    @DisplayName("ler o dono de uma conversa só suspensa não apaga a suspensão vencida: a varredura ainda a "
            + "encontra, e o dono é o tenant de quem suspendeu")
    void lerODonoNaoApagaAVencida() throws Exception {
        ConversationManager manager = new ConversationManager(Duration.ofMinutes(30),
                new JdbcSuspendedConversationStore(dataSource));
        ConversationService servico = new DefaultConversationService(manager,
                new JdbcConversationRepository(dataSource));
        // Direto pelo manager: a suspensão sem registro, como as gravadas antes da transcrição durável.
        manager.suspend("conv-6", null, null, formulario(), Duration.ofMillis(1),
                Map.of(DefaultConversationService.OWNER_METADATA, "tenant-b"));
        Thread.sleep(20);

        servico.addMessage(ConversationMessage.system("conv-6", "aviso"));

        assertThat(servico.getRecord("conv-6")).map(Conversation::tenantId).contains("tenant-b");
        assertThat(servico.cleanupExpired()).isEqualTo(1);
    }

    @Test
    @DisplayName("retirar o formulário de uma conversa só suspensa cria o registro no tenant do contexto, "
            + "antes de apagar a suspensão — o aviso seguinte não cai no tenant padrão")
    void retirarCriaORegistroNoTenantDoDono() {
        ConversationManager manager = new ConversationManager(Duration.ofMinutes(30),
                new JdbcSuspendedConversationStore(dataSource));
        ConversationService servico = new DefaultConversationService(manager,
                new JdbcConversationRepository(dataSource));
        manager.suspend("conv-7", "wf-1", "exec-1", formulario(), Duration.ofMinutes(30),
                Map.of(DefaultConversationService.OWNER_METADATA, "tenant-b"));

        assertThat(servico.withdrawForm("conv-7")).isPresent();
        servico.addMessage(ConversationMessage.system("conv-7", "A aprovação foi recusada"));

        ConversationService depois = processo();
        assertThat(depois.getRecord("conv-7")).map(Conversation::tenantId).contains("tenant-b");
        assertThat(depois.listConversationIds("tenant-b")).contains("conv-7");
        assertThat(depois.getMessages("conv-7")).extracting(ConversationMessage::content)
                .containsExactly("A aprovação foi recusada");
    }
}
