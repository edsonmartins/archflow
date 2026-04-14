package br.com.archflow.langchain4j.core.spi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LangChainRegistry")
class LangChainRegistryTest {

    private static final String PROVIDER_NAME = "test-provider";
    private static final String SUPPORTED_TYPE = "chat";
    private static final String UNSUPPORTED_TYPE = "embedding";

    @AfterEach
    void cleanUpFactories() throws Exception {
        getFactoriesMap().clear();
    }

    // -------------------------------------------------------------------------
    // createAdapter
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createAdapter throws IllegalArgumentException for unknown provider")
    void createAdapter_unknownProvider_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> LangChainRegistry.createAdapter("unknown", SUPPORTED_TYPE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("createAdapter throws IllegalArgumentException when provider does not support requested type")
    void createAdapter_unsupportedType_throwsIllegalArgumentException() throws Exception {
        registerFactory(stubFactory(PROVIDER_NAME, SUPPORTED_TYPE, null));

        assertThatThrownBy(() -> LangChainRegistry.createAdapter(PROVIDER_NAME, UNSUPPORTED_TYPE, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROVIDER_NAME)
                .hasMessageContaining(UNSUPPORTED_TYPE);
    }

    @Test
    @DisplayName("createAdapter returns adapter when provider is found and type is supported")
    void createAdapter_validProviderAndType_returnsAdapter() throws Exception {
        LangChainAdapter sentinel = stubAdapter();
        registerFactory(stubFactory(PROVIDER_NAME, SUPPORTED_TYPE, sentinel));

        LangChainAdapter result = LangChainRegistry.createAdapter(PROVIDER_NAME, SUPPORTED_TYPE, Map.of());

        assertThat(result).isSameAs(sentinel);
    }

    // -------------------------------------------------------------------------
    // hasProvider
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hasProvider returns false for an unknown provider")
    void hasProvider_unknownProvider_returnsFalse() {
        assertThat(LangChainRegistry.hasProvider("non-existent")).isFalse();
    }

    @Test
    @DisplayName("hasProvider returns true for a manually registered provider")
    void hasProvider_registeredProvider_returnsTrue() throws Exception {
        registerFactory(stubFactory(PROVIDER_NAME, SUPPORTED_TYPE, null));
        assertThat(LangChainRegistry.hasProvider(PROVIDER_NAME)).isTrue();
    }

    // -------------------------------------------------------------------------
    // getAvailableProviders
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getAvailableProviders returns empty set when no providers registered")
    void getAvailableProviders_empty() {
        assertThat(LangChainRegistry.getAvailableProviders()).isEmpty();
    }

    @Test
    @DisplayName("getAvailableProviders includes a manually registered provider")
    void getAvailableProviders_afterRegistration_includesProvider() throws Exception {
        registerFactory(stubFactory(PROVIDER_NAME, SUPPORTED_TYPE, null));
        assertThat(LangChainRegistry.getAvailableProviders()).containsExactly(PROVIDER_NAME);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, LangChainAdapterFactory> getFactoriesMap() throws Exception {
        Field f = LangChainRegistry.class.getDeclaredField("factories");
        f.setAccessible(true);
        return (Map<String, LangChainAdapterFactory>) f.get(null);
    }

    private static void registerFactory(LangChainAdapterFactory factory) throws Exception {
        getFactoriesMap().put(factory.getProvider(), factory);
    }

    /** Creates a stub adapter with no-op methods. */
    private static LangChainAdapter stubAdapter() {
        return new LangChainAdapter() {
            @Override public void configure(Map<String, Object> properties) {}
            @Override public Object execute(String operation, Object input,
                                            br.com.archflow.model.engine.ExecutionContext context) { return null; }
            @Override public void validate(Map<String, Object> properties) {}
            @Override public void shutdown() {}
        };
    }

    /** Creates a stub factory that supports only the given type. */
    private static LangChainAdapterFactory stubFactory(String provider, String supportedType,
                                                       LangChainAdapter adapterToReturn) {
        return new LangChainAdapterFactory() {
            @Override public String getProvider() { return provider; }
            @Override public LangChainAdapter createAdapter(Map<String, Object> properties) { return adapterToReturn; }
            @Override public boolean supports(String type) { return supportedType.equals(type); }
        };
    }
}
