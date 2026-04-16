package br.com.archflow.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Spring Boot entry point for the archflow REST API.
 *
 * <p>Starts the embedded Tomcat server and wires:
 * <ul>
 *   <li>REST controllers (from {@code br.com.archflow.api.web.*})</li>
 *   <li>Application services ({@link br.com.archflow.api.config.ArchflowBeanConfiguration})</li>
 *   <li>CORS for the Vite dev server</li>
 *   <li>Static frontend assets from the classpath (/static)</li>
 * </ul>
 *
 * <p>Run with: {@code mvn spring-boot:run -pl archflow-api}
 * or from a packaged jar: {@code java -jar archflow-api.jar}
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
