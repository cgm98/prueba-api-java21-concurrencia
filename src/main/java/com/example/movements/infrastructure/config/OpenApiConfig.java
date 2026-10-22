package com.example.movements.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Entorno Local / Docker Compose")
                ))
                .info(new Info()
                        .title("API de Movimientos Financieros Concurrentes")
                        .version("1.0.0")
                        .description("""
                                API RESTful bancaria de alta concurrencia diseñada para operaciones financieras transaccionales (retiros y transferencias).
                                
                                ### Características Principales:
                                - **Consistencia Transaccional Estricta:** Bloqueo pesimista en fila (`SELECT ... FOR UPDATE`) sobre MySQL 8.4 InnoDB en nivel de aislamiento `READ COMMITTED`.
                                - **Prevención de Deadlocks:** Ordenamiento determinista ascendente de IDs de cuenta en transferencias concurrentes.
                                - **Idempotencia Estricta con Detección de Manipulación:** Header obligatorio `Idempotency-Key` (UUID) con huella criptográfica SHA-256. Soporta repeticiones transparentes (`Idempotency-Replayed: true`) y bloquea intentos de ataque o payloads alterados (`HTTP 409 Conflict`).
                                - **Libro Mayor Inmutable (Ledger):** Ecuación contable verificable matemáticamente con balance en cuentas y asientos contables de partida simple/doble.
                                - **Java 21 Virtual Threads:** Rendimiento escalable sin saturación del pool de threads tradicionales del sistema operativo.
                                """)
                        .contact(new Contact()
                                .name("Senior Backend Engineering Team")
                                .email("engineering@financial.example.com"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
