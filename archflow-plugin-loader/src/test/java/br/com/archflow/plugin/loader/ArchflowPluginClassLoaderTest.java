package br.com.archflow.plugin.loader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ArchflowPluginClassLoader")
class ArchflowPluginClassLoaderTest {

    private ArchflowPluginClassLoader createLoader() {
        return new ArchflowPluginClassLoader(new URL[0], getClass().getClassLoader());
    }

    @Nested
    @DisplayName("shared package delegation")
    class SharedPackageDelegation {

        @Test
        @DisplayName("should delegate archflow model classes to parent")
        void shouldDelegateModelClasses() throws Exception {
            var loader = createLoader();

            // br.com.archflow.model is a shared package - should be loaded by parent
            Class<?> clazz = loader.loadClass("br.com.archflow.model.ai.type.ComponentType");
            assertThat(clazz).isNotNull();
            assertThat(clazz.getClassLoader()).isNotSameAs(loader);
        }

        @Test
        @DisplayName("should delegate archflow plugin api classes to parent")
        void shouldDelegatePluginApiClasses() throws Exception {
            var loader = createLoader();

            Class<?> clazz = loader.loadClass("br.com.archflow.plugin.api.spi.ComponentPlugin");
            assertThat(clazz).isNotNull();
            assertThat(clazz.getClassLoader()).isNotSameAs(loader);
        }

        @Test
        @DisplayName("should delegate java standard classes to parent")
        void shouldDelegateJavaClasses() throws Exception {
            var loader = createLoader();

            // java.lang classes always go to bootstrap loader
            Class<?> clazz = loader.loadClass("java.lang.String");
            assertThat(clazz).isSameAs(String.class);
        }
    }

    @Nested
    @DisplayName("plugin-first loading")
    class PluginFirstLoading {

        @Test
        @DisplayName("should fall back to parent for classes not in plugin URLs")
        void shouldFallbackToParent() throws Exception {
            var loader = createLoader();

            // Class not in plugin URLs - should fall back to parent
            Class<?> clazz = loader.loadClass("org.junit.jupiter.api.Test");
            assertThat(clazz).isNotNull();
        }

        @Test
        @DisplayName("should throw ClassNotFoundException for unknown classes")
        void shouldThrowForUnknownClasses() {
            var loader = createLoader();

            assertThatThrownBy(() -> loader.loadClass("com.nonexistent.FakeClass"))
                    .isInstanceOf(ClassNotFoundException.class);
        }

        @Test
        @DisplayName("should cache loaded classes")
        void shouldCacheLoadedClasses() throws Exception {
            var loader = createLoader();

            Class<?> first = loader.loadClass("java.lang.Integer");
            Class<?> second = loader.loadClass("java.lang.Integer");

            assertThat(first).isSameAs(second);
        }
    }
}
