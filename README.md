# API de Movimientos Financieros Concurrentes con Consistencia Transaccional e Idempotencia

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![MySQL 8.4](https://img.shields.io/badge/MySQL-8.4%20LTS-blue.svg)](https://www.mysql.com/)
[![Docker Compose](https://img.shields.io/badge/Docker%20Compose-v2+-blue.svg)](https://docs.docker.com/compose/)

Solución de alta concurrencia para movimientos financieros (retiros y transferencias) desarrollada para la prueba técnica **Senior Backend Engineer (Java 21, Spring Boot, MySQL)**.

---

## 🚀 Características Principales

- **Consistencia Transaccional Estricta:** Bloqueo pesimista de filas (`SELECT ... FOR UPDATE`) sobre transacciones ACID en MySQL InnoDB.
- **Prevención Matemática de Deadlocks:** Ordenamiento determinista ascendente de IDs de cuenta en transferencias concurrentes.
- **Idempotencia Transaccional:** Encabezado `Idempotency-Key` respaldado por índice único en base de datos y hash canónico SHA-256. Soporta retransmisiones idénticas (`Idempotency-Replayed: true`) y detecta colisiones con payload diferente (`HTTP 409 Conflict`).
- **Libro Mayor Inmutable (Ledger):** Cada movimiento aplicado genera asientos inmutables (`ledger_entries`) que permiten auditar y comprobar matemáticamente la ecuación contable:
  $$\text{balance} = \text{opening\_balance} + \sum \text{signed\_amount}$$
- **Java 21 Virtual Threads:** Habilitado tanto en el contenedor de servlets web como en el ejecutor de pruebas concurrentes (`ConcurrentRunner`).
- **Reintentos Acotados con Jitter:** `OperationExecutor` gestiona reintentos exponenciales fuera de la transacción para absorber picos de contención y bloqueos transitorios.
- **Docker y Docker Compose:** Despliegue completo con contenedor MySQL 8.4 optimizado y contenedor de la API en tiempo de ejecución (JRE Eclipse Temurin 21) endurecido con usuario sin privilegios.

---

## 📋 Requisitos del Sistema

- **Java 21 JDK** (ej. Eclipse Temurin 21)
- **Docker** y **Docker Compose**
- **Maven 3.9+** (o el Maven Wrapper `./mvnw` incluido)

---

## 🐳 Despliegue con Docker Compose

Para compilar el artefacto y levantar la base de datos MySQL y la API automáticamente:

```bash
# 1. Empaquetar el artefacto JAR ejecutable (omitiendo tests para agilizar el build inicial)
./mvnw clean package -DskipTests

# 2. Levantar la base de datos y la API en segundo plano
docker compose up --build -d

# 3. Comprobar que los contenedores estén saludables (UP & healthy)
docker compose ps

# 4. Ver los logs de la aplicación
docker compose logs -f api
```

La API estará lista y escuchando en `http://localhost:8080`.

- **Swagger UI Interactivo:** [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Especificación OpenAPI 3 (JSON):** [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)
- **Archivo OpenAPI 3 estático (YAML):** [docs/openapi.yaml](docs/openapi.yaml)

Para detener los contenedores y remover volúmenes:
```bash
docker compose down -v
```

---

## 💻 Ejecución Local (Desarrollo)

Si prefieres levantar solo la base de datos en Docker y ejecutar Spring Boot localmente:

```bash
# 1. Levantar únicamente el contenedor de MySQL 8.4
docker compose up -d mysql

# 2. Ejecutar la aplicación con Maven Wrapper
./mvnw spring-boot:run
```

---

## 🧪 Ejecución de Pruebas Automatizadas

El proyecto cuenta con un conjunto exhaustivo de pruebas unitarias, de contrato y de integración de concurrencia real ejecutadas contra un contenedor MySQL:

```bash
# Ejecutar toda la suite de pruebas automatizadas
./mvnw test
```

### Escenarios de Concurrencia Verificados:

1. **Escenario A (1,000 retiros concurrentes):**
   - Saldo inicial: S/ 10,000.00
   - 1,000 retiros concurrentes de S/ 5.00
   - Resultado esperado y verificado: 1,000 retiros aplicados, saldo final exacto S/ 5,000.00.
2. **Escenario B (500 transferencias concurrentes):**
   - Cuenta A: S/ 10,000.00, Cuenta B: S/ 0.00
   - 500 transferencias concurrentes de A hacia B de S/ 10.00
   - Resultado: A = S/ 5,000.00, B = S/ 5,000.00, saldo total conservado = S/ 10,000.00.
3. **Prevención de Deadlocks (Transferencias Cruzadas):**
   - Transferencias simultáneas A→B y B→A compitiendo por los mismos recursos.
   - Demostración de ausencia de interbloqueos mediante orden de bloqueo ascendente.
4. **Sobredemanda de Fondos:**
   - 3,000 retiros concurrentes de S/ 5.00 sobre saldo de S/ 10,000.00.
   - Exactamente 2,000 aplicadas, 1,000 rechazadas con HTTP 422 `INSUFFICIENT_FUNDS`.
   - Saldo final 0.00, **nunca negativo**, respaldado por restricción `CHECK (balance >= 0)`.
5. **Idempotencia Estricta:**
   - 50 hilos concurrentes enviando la misma clave y payload: exactamente 1 aplicado, 49 replayed, 1 único débito contable.
   - Reutilización de clave con importe distinto produce `HTTP 409 Conflict`.
6. **Atomicidad y Rollback:**
   - Inyección de fallas en medio de una transferencia: los saldos y asientos se revierten por completo sin dejar estados huérfanos.

---

## 📖 Especificación de la API

La especificación completa OpenAPI 3.0 se encuentra en [`docs/openapi.yaml`](docs/openapi.yaml).

### 1. Retiro de Fondos
```bash
curl -i -X POST http://localhost:8080/api/v1/withdrawals \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "accountId": "11111111-1111-4111-8111-111111111111",
    "amount": "5.00",
    "currency": "PEN"
  }'
```

**Respuesta Exitosa (HTTP 201 Created):**
```json
{
  "operationId": "d3b07384-d113-4a11-8c01-739462846193",
  "type": "WITHDRAWAL",
  "status": "APPLIED",
  "sourceAccountId": "11111111-1111-4111-8111-111111111111",
  "destinationAccountId": null,
  "amount": "5.00",
  "currency": "PEN",
  "resultCode": "OK",
  "createdAt": "2026-10-22T16:00:00Z"
}
```

### 2. Transferencia entre Cuentas
```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "sourceAccountId": "11111111-1111-4111-8111-111111111111",
    "destinationAccountId": "22222222-2222-4222-8222-222222222222",
    "amount": "10.00",
    "currency": "PEN"
  }'
```

### 3. Consultar Saldo Confirmado de una Cuenta
```bash
curl -i -X GET http://localhost:8080/api/v1/accounts/11111111-1111-4111-8111-111111111111
```

### 4. Consultar Resultado de una Operación
```bash
curl -i -X GET http://localhost:8080/api/v1/operations/d3b07384-d113-4a11-8c01-739462846193
```

### 5. Intento de Ataque con Payload Modificado (Reutilización Maliciosa de Idempotency-Key)
Si un atacante intenta enviar una solicitud usando un `Idempotency-Key` ya procesado pero alterando los datos del cuerpo (monto, cuenta de destino o tipo de movimiento):
```bash
# Envío con la misma clave pero alterando el monto a 500.00
curl -i -X POST http://localhost:8080/api/v1/withdrawals \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: a0000000-0000-0000-0000-000000000001" \
  -d '{
    "accountId": "11111111-1111-4111-8111-111111111111",
    "amount": "500.00",
    "currency": "PEN"
  }'
```
**Respuesta de Conflicto y Bloqueo de Seguridad (HTTP 409 Conflict):**
```json
{
  "type": "urn:problem:idempotency-key-reused",
  "title": "Clave idempotente reutilizada con diferente payload",
  "status": 409,
  "detail": "La clave idempotente ya ha sido procesada previamente con un payload diferente. Por seguridad e integridad transaccional, no es posible reutilizar una clave existente con diferente cuerpo. Genere un nuevo Idempotency-Key para esta operación.",
  "instance": "/api/v1/withdrawals",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "retryable": false,
  "idempotencyKey": "a0000000-0000-0000-0000-000000000001",
  "existingOperationId": "253380d3-e49d-4e6a-8944-9f876ce688f4",
  "existingStatus": "APPLIED",
  "securityWarning": "IDEMPOTENCY_PAYLOAD_TAMPERING_BLOCKED"
}
```
*Garantía*: Los saldos y libros mayores (`ledger_entries`) permanecen 100% inmutables y se emite una alerta de seguridad en los logs para auditoría.

---

## 📚 Documentación y Entregables Formales
 
- **[Directorio de Entregables Formales (Punto 7)](entregables/README.md)**
  - [Diagramas de Flujo y Límite Transaccional](entregables/DIAGRAMA_FLUJO_TRANSACCIONAL.md)
  - [Nota Técnica de Concurrencia, Trade-Offs y Limitaciones](entregables/NOTA_TECNICA_CONCURRENCIA.md)
  - [Resultados de Pruebas y Metodología de Medición](entregables/RESULTADOS_Y_METODOLOGIA_PRUEBAS.md)
- [Nota Técnica de Sustentación Senior (Detallada)](docs/technical-note.md)
- [Arquitectura Detallada y Flujos Mermaid](docs/architecture.md)
- [Contrato OpenAPI 3.0](docs/openapi.yaml) (Swagger/OpenAPI)
