# Entregable 1: Repositorio, Requisitos y Guía de Ejecución

Este documento consolida las instrucciones de ejecución, despliegue y pruebas del proyecto **API de Movimientos Financieros Concurrentes con Consistencia Transaccional e Idempotencia**, correspondiente a la prueba técnica **Senior Backend Engineer (Java 21, Spring Boot, MySQL)**.

---

## 1. Requisitos del Entorno

| Componente | Versión Mínima Requerida | Propósito |
| :--- | :--- | :--- |
| **JDK** | Java 21 LTS (Eclipse Temurin / OpenJDK) | Runtime con soporte nativo para Virtual Threads |
| **Build Tool** | Apache Maven 3.9+ (o `./mvnw` incluido) | Gestión de dependencias y empaquetado JAR |
| **Container Engine** | Docker 24.0+ y Docker Compose v2+ | Aislamiento de base de datos MySQL y Testcontainers |
| **Base de Datos** | MySQL 8.4 LTS (InnoDB Engine) | Motor transaccional ACID con aislamiento READ COMMITTED |

---

## 2. Estructura de Entregables del Punto 7

Los entregables formales solicitados se encuentran organizados en este directorio:

```
entregables/
├── README.md                              # Guía consolidada de ejecución y repositorio (Este archivo)
├── DIAGRAMA_FLUJO_TRANSACCIONAL.md        # Diagramas de flujo con límites transaccionales exactos
├── NOTA_TECNICA_CONCURRENCIA.md           # Nota técnica de 2 páginas (estrategia, trade-offs, riesgos)
└── RESULTADOS_Y_METODOLOGIA_PRUEBAS.md    # Resultados de pruebas de concurrencia y metodología
```

---

## 3. Comandos de Compilación y Ejecución

### Opción A: Despliegue Completo en Contenedores (Docker Compose)

Para compilar la aplicación y levantar tanto MySQL 8.4 como la API Spring Boot de forma contenerizada:

```bash
# 1. Empaquetar el artefacto JAR ejecutable en el host
./mvnw clean package -DskipTests

# 2. Construir la imagen Docker y levantar los servicios en segundo plano
docker compose up --build -d

# 3. Verificar que ambos contenedores estén saludables (UP & healthy)
docker compose ps

# 4. Inspeccionar los logs de la API
docker compose logs -f api
```

La aplicación quedará disponible en:
- **API Base:** `http://localhost:8080/api/v1`
- **Swagger UI Interactivo:** `http://localhost:8080/swagger-ui.html`
- **OpenAPI 3 JSON Spec:** `http://localhost:8080/v3/api-docs`
- **Actuator Health:** `http://localhost:8080/actuator/health`

Para detener y limpiar los contenedores:
```bash
docker compose down -v
```

---

### Opción B: Ejecución Local para Desarrollo

Si desea ejecutar la base de datos en Docker y la aplicación Spring Boot directamente en el host:

```bash
# 1. Levantar únicamente el contenedor de MySQL 8.4
docker compose up -d mysql

# 2. Ejecutar la aplicación Spring Boot con Maven Wrapper
./mvnw spring-boot:run
```

---

## 4. Ejecución de la Suite de Pruebas Automatizadas

El proyecto cuenta con pruebas unitarias, de contrato HTTP y de concurrencia masiva real ejecutadas contra un contenedor MySQL efímero mediante **Testcontainers**:

```bash
# Ejecutar todas las pruebas unitarias y de integración
./mvnw test
```

Para ejecutar suites específicas:

```bash
# Ejecutar solo pruebas de concurrencia de retiros (1,000 hilos)
./mvnw test -Dtest=WithdrawalConcurrencyIT

# Ejecutar solo pruebas de transferencias y deadlocks (500 hilos y cross-transfers)
./mvnw test -Dtest=TransferConcurrencyIT

# Ejecutar pruebas de idempotencia bajo contención (50 hilos misma clave)
./mvnw test -Dtest=IdempotencyIT

# Ejecutar pruebas de sobregiro y fondos insuficientes (3,000 hilos)
./mvnw test -Dtest=InsufficientFundsIT

# Ejecutar pruebas de rollback transaccional ante fallos
./mvnw test -Dtest=TransferRollbackIT
```

---

## 5. Ejemplos de Invocación HTTP (cURL)

### Retiro de Fondos
```bash
curl -i -X POST http://localhost:8080/api/v1/withdrawals \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: c9b1d842-8c1e-450a-9d01-e23a6f44d8b1" \
  -H "X-Client-Id: client-retail-01" \
  -d '{
    "accountId": "11111111-1111-4111-8111-111111111111",
    "amount": "25.50",
    "currency": "PEN"
  }'
```

### Transferencia entre Cuentas
```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: e82f1a92-23b0-4f59-a78b-3d44e590c128" \
  -H "X-Client-Id: client-retail-01" \
  -d '{
    "sourceAccountId": "11111111-1111-4111-8111-111111111111",
    "destinationAccountId": "22222222-2222-4222-8222-222222222222",
    "amount": "100.00",
    "currency": "PEN"
  }'
```

### Consulta de Operación (Auditoría e Idempotencia)
```bash
curl -i -X GET http://localhost:8080/api/v1/operations/c9b1d842-8c1e-450a-9d01-e23a6f44d8b1
```
