package br.com.archflow.plugin.loader;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * ClassLoader para jars de plugin do archflow.
 *
 * <h2>Estratégia de delegação (child-first com fallback total ao pai)</h2>
 * <ol>
 *   <li>Pacotes compartilhados ({@code br.com.archflow.model},
 *       {@code br.com.archflow.plugin.api}, {@code dev.langchain4j},
 *       {@code org.apache.camel}) são SEMPRE carregados do classloader pai,
 *       para que plugin e aplicação compartilhem as mesmas classes de API.</li>
 *   <li>Demais classes: tenta primeiro nos jars do plugin (child-first) e,
 *       se não encontrar, <strong>cai no classloader pai</strong>.</li>
 * </ol>
 *
 * <p><strong>Consequências práticas:</strong>
 * <ul>
 *   <li>O fallback ao pai é TOTAL — plugins enxergam qualquer classe da
 *       aplicação hospedeira. Isso NÃO é uma barreira de visibilidade nem de
 *       segurança; só evita conflito de versão quando o plugin embute a sua
 *       própria cópia de uma biblioteca.</li>
 *   <li>Jars de plugin devem ser <strong>fat-jars</strong>: não existe
 *       resolução de dependências em runtime (a antiga promessa de "Jeka"
 *       nunca foi implementada). Dependência ausente do jar e do classpath
 *       da aplicação = {@code ClassNotFoundException}.</li>
 *   <li>Não há sandbox: código carregado por este classloader roda com os
 *       mesmos privilégios da JVM. Só carregue jars confiáveis — ver a
 *       fronteira de confiança documentada em {@link ArchflowPluginManager}.</li>
 * </ul>
 */
public class ArchflowPluginClassLoader extends URLClassLoader {

    private static final List<String> SHARED_PACKAGES = Arrays.asList(
            "br.com.archflow.model",           // Novo - para acessar interfaces base
            "br.com.archflow.plugin.api",      // Atualizado - novo pacote
            "dev.langchain4j",                 // Mantido
            "org.apache.camel"                 // Novo - para suporte a rotas
    );

    private final ClassLoader parentClassLoader;

    public ArchflowPluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, null);
        this.parentClassLoader = parent;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) 
            throws ClassNotFoundException {
        
        Class<?> loadedClass = findLoadedClass(name);
        
        if (loadedClass == null) {
            boolean isSharedClass = SHARED_PACKAGES.stream()
                .anyMatch(name::startsWith);

            if (isSharedClass) {
                loadedClass = parentClassLoader.loadClass(name);
            } else {
                try {
                    loadedClass = super.loadClass(name, resolve);
                } catch (ClassNotFoundException e) {
                    loadedClass = parentClassLoader.loadClass(name);
                }
            }
        }

        if (resolve) {
            resolveClass(loadedClass);
        }

        return loadedClass;
    }
}