package br.com.archflow.langchain4j.memory.jdbc;

import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcMemoryAdapterTest {

    @Nested
    @DisplayName("validate()")
    class Validate {

        @Test
        @DisplayName("throws on null properties")
        void nullProperties() {
            var adapter = new JdbcMemoryAdapter();

            assertThatThrownBy(() -> adapter.validate(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("throws when datasource is missing")
        void missingDatasource() {
            var adapter = new JdbcMemoryAdapter();
            var props = Map.<String, Object>of();

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DataSource");
        }

        @Test
        @DisplayName("throws when datasource is explicitly null")
        void explicitNullDatasource() {
            var adapter = new JdbcMemoryAdapter();
            var props = new HashMap<String, Object>();
            props.put("datasource", null);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DataSource");
        }

        @Test
        @DisplayName("succeeds with valid datasource")
        void validDatasource() {
            var adapter = new JdbcMemoryAdapter();
            var props = Map.<String, Object>of("datasource", stubDataSource());

            // should not throw
            adapter.validate(props);
        }
    }

    @Nested
    @DisplayName("Factory")
    class FactoryTests {

        private final JdbcMemoryAdapter.Factory factory = new JdbcMemoryAdapter.Factory();

        @Test
        @DisplayName("getProvider returns jdbc")
        void provider() {
            assertThat(factory.getProvider()).isEqualTo("jdbc");
        }

        @Test
        @DisplayName("supports memory type")
        void supportsMemory() {
            assertThat(factory.supports("memory")).isTrue();
        }

        @Test
        @DisplayName("does not support chat type")
        void doesNotSupportChat() {
            assertThat(factory.supports("chat")).isFalse();
        }

        @Test
        @DisplayName("does not support null type")
        void doesNotSupportNull() {
            assertThat(factory.supports(null)).isFalse();
        }

        @Test
        @DisplayName("does not support empty type")
        void doesNotSupportEmpty() {
            assertThat(factory.supports("")).isFalse();
        }

        @Test
        @DisplayName("does not support model type")
        void doesNotSupportModel() {
            assertThat(factory.supports("model")).isFalse();
        }

        @Test
        @DisplayName("createAdapter returns LangChainAdapter instance")
        void createAdapter() {
            var props = Map.<String, Object>of("datasource", stubDataSource());
            LangChainAdapter adapter = factory.createAdapter(props);

            assertThat(adapter).isInstanceOf(JdbcMemoryAdapter.class);
        }

        @Test
        @DisplayName("createAdapter throws on invalid properties")
        void createAdapterInvalidProps() {
            var props = Map.<String, Object>of();

            assertThatThrownBy(() -> factory.createAdapter(props))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("configure()")
    class Configure {

        @Test
        @DisplayName("configure delegates to validate")
        void configureDelegatesToValidate() {
            var adapter = new JdbcMemoryAdapter();

            assertThatThrownBy(() -> adapter.configure(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("configure accepts valid properties without maxMessages")
        void configureDefaultMaxMessages() {
            var adapter = new JdbcMemoryAdapter();
            var props = Map.<String, Object>of("datasource", stubDataSource());

            // should not throw; maxMessages defaults to 100
            adapter.configure(props);
        }

        @Test
        @DisplayName("configure accepts custom maxMessages")
        void configureCustomMaxMessages() {
            var adapter = new JdbcMemoryAdapter();
            var props = Map.<String, Object>of(
                    "datasource", stubDataSource(),
                    "memory.maxMessages", 50
            );

            // should not throw
            adapter.configure(props);
        }
    }

    @Nested
    @DisplayName("shutdown()")
    class Shutdown {

        @Test
        @DisplayName("shutdown completes without error")
        void shutdownClean() {
            var adapter = new JdbcMemoryAdapter();
            adapter.configure(Map.of("datasource", stubDataSource()));

            // should not throw
            adapter.shutdown();
        }

        @Test
        @DisplayName("shutdown on unconfigured adapter does not throw")
        void shutdownUnconfigured() {
            var adapter = new JdbcMemoryAdapter();

            // datasource is null; shutdown should still be safe
            adapter.shutdown();
        }
    }

    /**
     * Minimal DataSource stub that satisfies validate() without any real connection.
     */
    private static DataSource stubDataSource() {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                throw new SQLException("stub - no real connection");
            }
            @Override public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("stub - no real connection");
            }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }
}
