package br.com.archflow.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aplicação exemplo de integração archflow com Spring Boot.
 *
 * Este exemplo demonstra:
 * - Criação de workflows programaticamente
 * - Uso de agentes AI com ferramentas customizadas
 * - Streaming de respostas via SSE
 * - Configuração de métricas e observabilidade
 */
@SpringBootApplication
public class ArchflowExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchflowExampleApplication.class, args);
    }
}
