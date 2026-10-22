# Nota Técnica: Consistencia Transaccional bajo Alta Concurrencia e Idempotencia

**Proyecto:** API de Movimientos Financieros Concurrentes  
**Rol:** Senior Backend Engineer  
**Stack:** Java 21 (Virtual Threads), Spring Boot 3.3.5, Spring JDBC, MySQL 8.4 (InnoDB), Flyway, Docker Compose  

---

## 1. Estrategia de Concurrencia Elegida y Justificación

Para garantizar la integridad estricta del saldo en cuentas con alta contención ("hot accounts"), se implementó una estrategia de **Bloqueo Pesimista Explícito a nivel de fila (`SELECT ... FOR UPDATE`) combinado con Ordenamiento Global de Locks**.

### Justificación:
1. **Evitar Desperdicio de CPU bajo Contención Extrema:** Bajo ráfagas masivas (1,000 a 10,000 peticiones concurrentes sobre la misma cuenta), el control optimista con `@Version` causaría una tasa de colisiones cercana al 99.9%, obligando a miles de transacciones a abortar y reintentar, degradando el throughput del pool de conexiones.
2. **Serialización Justa en el Motor InnoDB:** Al utilizar `SELECT ... FOR UPDATE` sobre el índice primario (`id`), InnoDB pone en cola las transacciones de manera eficiente. Cada transacción lee el saldo ya consolidado por la transacción anterior y valida las reglas de negocio sobre el estado real.
3. **Consistencia Transaccional Completa:** A diferencia de un `UPDATE` condicional aislado (`UPDATE accounts SET balance = balance - :amount WHERE id = :id AND balance >= :amount`), nuestra operación requiere una transacción atómica que sincronice el débito/crédito, la inserción del asiento inmutable en el libro mayor (`ledger_entries`) y la reserva/confirmación de la clave de idempotencia (`operations`).

---

## 2. Alternativas Consideradas y Trade-offs

| Alternativa | Ventajas | Desventajas / Trade-offs | Evaluación |
| :--- | :--- | :--- | :--- |
| **Bloqueo Pesimista (`SELECT FOR UPDATE`)** | - Control determinista sobre cuentas calientes.<br>- Cero abortos por desactualización de versión.<br>- Garantiza que el saldo leído es el actual. | - Latencia dependiente de la longitud de la cola de locks.<br>- Riesgo de deadlocks si no se ordenan los recursos. | **ELEGIDA:** Óptima para contención agresiva, combinada con orden ascendente de IDs y reintentos acotados. |
| **Control Optimista (`@Version`)** | - Sin bloqueos en base de datos durante la lectura.<br>- Excelente para baja contención y lecturas masivas. | - Abortos masivos (*OptimisticLockException*) en cuentas concurrentes.<br>- Sobrecarga inútil de CPU y reintentos. | **Descartada:** Colapsa ante 1,000 hilos compitiendo por una sola cuenta. |
| **UPDATE Condicional Atómico** | - Muy eficiente para retiros simples de una sola cuenta. | - No resuelve transferencias entre múltiples cuentas.<br>- Dificulta la coordinación con asientos y claves de idempotencia. | **Descartada:** Insuficiente para un ledger de partida doble y transferencias coordinadas. |
| **Nivel de Aislamiento `SERIALIZABLE`** | - Garantías matemáticas universales sin locks manuales. | - Elevadísima tasa de deadlocks (código 1213) y abortos por predicados fantasma en InnoDB. | **Descartada:** Excesivamente costosa e impredecible frente a `READ COMMITTED` + locks explícitos. |
| **Sincronización en Memoria (`synchronized` / `ReentrantLock`)** | - Muy rápido en una sola JVM. | - No escala horizontalmente; no protege múltiples pods/instancias de la API. | **Descartada:** Inaceptable para arquitecturas distribuidas modernas. |

---

## 3. Prevención de Deadlocks en Transferencias Concurrentes

El riesgo clásico en transferencias simultáneas ocurre cuando:
- Hilo 1: Transfiere de Cuenta A a Cuenta B (bloquea A, luego intenta bloquear B).
- Hilo 2: Transfiere de Cuenta B a Cuenta A (bloquea B, luego intenta bloquear A).
Esto genera un **abrazo mortal (Deadlock)**.

**Solución Implementada:**  
En `MovementService`, antes de solicitar cualquier bloqueo, los IDs de las cuentas se ordenan de forma estricta y ascendente por su valor alfanumérico (UUID):
```java
UUID firstId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
UUID secondId = sourceId.compareTo(destId) < 0 ? destId : sourceId;

Account first = accountRepository.findByIdForUpdate(firstId);
Account second = accountRepository.findByIdForUpdate(secondId);
```
Al obligar a todas las transacciones del sistema a adquirir los bloqueos en el mismo orden global, **los grafos de espera cíclica se vuelven matemáticamente imposibles**.

---

## 4. Respuestas a las Preguntas de Sustentación Senior

### 1. ¿Qué condición de carrera podría ocurrir si dos transacciones leen el mismo saldo y luego lo actualizan?
**Respuesta:**  
La condición de carrera clásica es la **Pérdida de Actualización (Lost Update)**.  
Si la Cuenta A tiene S/ 100 y dos transacciones T1 y T2 leen concurrentemente S/ 100 sin bloqueo:
- T1 decide debitar S/ 60 (nuevo saldo calculado = S/ 40).
- T2 decide debitar S/ 70 (nuevo saldo calculado = S/ 30, creyendo que aún hay S/ 100).
- Si ambas ejecutan `UPDATE accounts SET balance = :nuevoSaldo`: T2 sobrescribirá a T1. El saldo quedará en S/ 30 en lugar de fallar o quedar en -S/ 30. Se habrán retirado S/ 130 cuando solo existían S/ 100.
Con `SELECT ... FOR UPDATE`, T2 espera a que T1 termine y haga commit; cuando T2 adquiere el lock, lee el saldo actualizado (S/ 40) y rechaza la operación por fondos insuficientes (`INSUFFICIENT_FUNDS`). Además, la base de datos cuenta con una restricción dura `CHECK (balance >= 0)`.

### 2. ¿Por qué `@Transactional` por sí solo no garantiza que el saldo sea correcto?
**Respuesta:**  
`@Transactional` proporciona **atomicidad** (todo o nada) y delimita el alcance de la conexión JDBC, pero su nivel de aislamiento por defecto en MySQL (`REPEATABLE READ`) o en configuraciones comunes (`READ COMMITTED`) **no evita lecturas concurrentes no bloqueantes**. Dos hilos bajo transacciones separadas pueden ejecutar un `SELECT` simultáneo sin interferir entre sí. `@Transactional` no es un cerrojo mutex: se requiere una cláusula de bloqueo explícita (`FOR UPDATE`) o una restricción de unicidad para forzar la serialización en las filas disputadas.

### 3. ¿Qué estrategia eligió y qué ocurre cuando dos operaciones compiten por la misma cuenta?
**Respuesta:**  
Se eligió bloqueo pesimista en fila (`SELECT ... FOR UPDATE`) sobre aislamiento `READ COMMITTED`.  
Cuando dos operaciones compiten:
1. La primera transacción adquiere el lock exclusivo sobre el registro de la cuenta en InnoDB.
2. La segunda transacción se encola en el gestor de bloqueos de MySQL.
3. Al hacer commit la primera transacción, los cambios de saldo y asientos quedan confirmados y el lock se libera.
4. La segunda transacción adquiere el lock inmediatamente, lee el saldo consolidado y valida si los fondos restantes cubren el nuevo importe. Si no alcanzan, genera un rechazo de negocio (HTTP 422) persistido sin alterar el saldo ni crear asientos.

### 4. ¿Cómo evita que un reintento del cliente duplique un movimiento?
**Respuesta:**  
Se implementó un patrón de **Idempotencia Transaccional con Reserva Atómica**:
1. El cliente envía un encabezado obligatorio `Idempotency-Key` (UUID).
2. Se genera un hash criptográfico SHA-256 canónico del payload (`tipo:origen:destino:monto:moneda`).
3. En la tabla `operations`, existe un índice único `UNIQUE KEY (client_id, idempotency_key)`.
4. Al inicio de la transacción se intenta insertar la operación con estado `PENDING`.
   - **Si se adquiere:** La transacción continúa, aplica el movimiento y actualiza la fila con el estado final (`APPLIED` o `REJECTED`), el código HTTP y el payload JSON antes del commit.
   - **Si ya existía (clave ya procesada o colisión concurrente):** Se recupera la operación registrada y se compara el hash criptográfico:
     - **Payload Idéntico (Reintento legítimo):** Se reproduce exactamente la respuesta persistida (`201` o `422`) agregando el encabezado `Idempotency-Replayed: true`, sin volver a debitar la cuenta ni insertar nuevos asientos contables.
     - **Payload Alterado (Ataque de Manipulación / Secuestro de Clave):** Si un atacante o cliente interceptado intenta reutilizar un `Idempotency-Key` ya procesado pero altera el cuerpo (modificando el monto, la cuenta destino o cambiando de retiro a transferencia):
       - Se detecta la discrepancia criptográfica (`originalHash != incomingHash`).
       - Se emite una alerta de auditoría forense en los logs (`ALERTA DE SEGURIDAD: Se detectó intento de reutilización de Idempotency-Key con payload modificado`).
       - Se bloquea la transacción arrojando `IdempotencyConflictException`, mapeada a **`HTTP 409 Conflict` (RFC 7807 ProblemDetail)** con código `IDEMPOTENCY_KEY_REUSED` y propiedad `securityWarning: IDEMPOTENCY_PAYLOAD_TAMPERING_BLOCKED`.
       - Se garantiza que el saldo y el libro mayor contable quedan **100% inalterados**.

### 5. ¿Cómo garantizaría la atomicidad si las cuentas estuvieran en bases de datos distintas?
**Respuesta:**  
Si las cuentas residen en bases de datos independientes o microservicios desacoplados:
- **Transacciones Distribuidas (2PC / XA):** Proporcionan atomicidad estricta coordinando un commit en dos fases, pero introducen acoplamiento temporal, alta latencia y bloqueos prolongados sobre las bases de datos.
- **Patrón Saga Orquestada con Consistencia Eventual (Recomendado):**
  1. La cuenta de origen reserva/bloquea los fondos mediante un estado `PENDING_DEBIT`.
  2. Se emite un evento transaccional (vía *Transactional Outbox Pattern* y Kafka/RabbitMQ) hacia el servicio de destino.
  3. El servicio de destino aplica el crédito con idempotencia.
  4. Si el destino falla de forma irrecuperable, se dispara una **transacción de compensación** que cancela la reserva en el origen y repone los fondos.

### 6. ¿Qué métricas, logs y alertas agregaría para diagnosticar contención, deadlocks y fallos de transacción?
**Respuesta:**  
- **Métricas (Micrometer / Prometheus):**
  - Latencia percentil p50/p95/p99 de transacciones financieras (`timer: movements.execution.time`).
  - Tasa de espera y adquisición de conexiones en el pool Hikari (`hikaricp.connections.pending`, `hikaricp.connections.timeout`).
  - Contador de reintentos por contención (`counter: movements.retries.total`).
  - Contador de deadlocks detectados (`counter: db.deadlocks.total`).
  - Tasa de rechazos de negocio vs errores 5xx.
- **Logs Estructurados (JSON / MDC):**
  - Cada log incluye: `traceId`, `spanId`, `clientId`, `idempotencyKey`, `operationId`, `durationMs`, `attempt`, `sqlState`.
- **Alertas (Prometheus / Alertmanager):**
  - *Alerta Crítica:* `hikaricp.connections.pending > 10` por más de 1 minuto (riesgo de saturación).
  - *Alerta de Integridad:* Discrepancia detectada en la conciliación matemática del ledger (`balance != opening_balance + sum(ledger)`).
  - *Alerta de Rendimiento:* p99 de transferencias superando 500ms sostenido.
