package br.com.archflow.plugin.loader;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * ClassLoader específico para plugins do archflow.
 * Garante isolamento e controle de acesso às classes compartilhadas.
 */
public class ArchflowPluginClassLoader extends URLClassLoader {
    
    private static final List<String> SHARED_PACKAGES = Arrays.asList(
        "br.com.archflow.core.plugin.spi",
        "br.com.archflow.core.plugin.metadata",
        "dev.langchain4j"
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