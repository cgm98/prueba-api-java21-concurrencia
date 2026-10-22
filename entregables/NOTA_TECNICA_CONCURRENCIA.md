# Entregable 3: Nota Técnica de Concurrencia, Trade-Offs y Limitaciones

**Proyecto:** API de Movimientos Financieros Concurrentes  
**Autor:** Senior Backend Engineer  
**Stack Base:** Java 21 LTS (Virtual Threads), Spring Boot 3.3.5, Spring JDBC, MySQL 8.4 LTS (InnoDB Engine)  

---

## 1. Estrategia de Concurrencia Seleccionada

Para asegurar la integridad contable bajo alta contención intencional sobre cuentas disputadas ("hot accounts"), se implementó una arquitectura basada en **Bloqueo Pesimista Fila a Fila (`SELECT ... FOR UPDATE`) combinado con Ordenamiento Global Determinista de Recursos y Reintentos Acotados con Jitter Exponencial fuera de la Transacción**.

### Justificación Técnica:
1. **Comportamiento ante Ráfagas Masivas:** Bajo concurrencia extrema (1,000 hilos compitiendo por la misma cuenta bancaria), el control optimista (`@Version`) genera una tasa de colisión superior al 99%, forzando reintentos en bucle que saturan la CPU de la JVM y agotan el pool de conexiones. El bloqueo pesimista pone a los hilos en espera en el gestor de locks de InnoDB, permitiendo que cada transacción procese el saldo consolidado exacto en orden secuencial sin abortos espurios.
2. **Coordinación Multirregistro Atómica:** A diferencia de un simple saldo en memoria, una transferencia financiera requiere modificar dos cuentas, registrar dos asientos inmutables en `ledger_entries` y confirmar el estado de idempotencia en `operations`. El bloqueo pesimista en la fase de lectura garantiza que la validación de fondos y los cambios posteriores se basen en un estado consolidado.
3. **Eliminación Determinista de Deadlocks:** El ordenamiento lexicográfico estricto de IDs de cuenta (`min(idA, idB)` seguido de `max(idA, idB)`) convierte el grafo de asignación de recursos en acíclico, eliminando matemáticamente los deadlocks causados por transferencias cruzadas simultáneas ($A \rightarrow B$ y $B \rightarrow A$).

---

## 2. Alternativas Arquitecturales Consideradas

| Alternativa | Ventajas Técnicas | Desventajas / Trade-offs Críticos | Decisión |
| :--- | :--- | :--- | :--- |
| **Bloqueo Pesimista (`SELECT FOR UPDATE`)** | - Consistencia determinista.<br>- Cero abortos por desincronización de versión.<br>- Saldo validado sobre estado confirmado. | - Latencia dependiente del encolamiento en el motor.<br>- Riesgo de contención en el pool HikariCP. | **ELEGIDA:** Óptima para contención agresiva con orden determinista y backoff. |
| **Control Optimista (`@Version`)** | - Lecturas no bloqueantes en base de datos.<br>- Rendimiento óptimo en cuentas de baja disputa. | - Tormenta de excepciones (`OptimisticLockException`) en cuentas calientes.<br>- Desperdicio masivo de CPU y conexiones por reintentos repetitivos. | **Descartada:** Colapso total ante 1,000 hilos concurrentes sobre una misma cuenta. |
| **UPDATE Condicional Atómico**<br>(`SET balance = balance - :amt WHERE balance >= :amt`) | - Rendimiento extremadamente alto en débitos simples de una sola fila. | - Insuficiente para transferencias atómicas de dos cuentas y registro simultáneo de libro mayor e idempotencia. | **Descartada:** No satisface las reglas de negocio de partida doble y auditoría. |
| **Aislamiento `SERIALIZABLE`** | - Garantías matemáticas sin locks explícitos en código. | - Elevadísima tasa de deadlocks (MySQL 1213) y abortos forzados por predicados fantasma en InnoDB. | **Descartada:** Impredecible e ineficiente frente a `READ COMMITTED` + locks manuales. |
| **Sincronización en JVM (`ReentrantLock`)** | - Latencia ultrabaja de bloqueo en memoria local. | - Inviable en despliegues distribuidos multi-instancia (no protege entre pods). | **Descartada:** Incompatible con arquitecturas cloud nativas horizontales. |

---

## 3. Riesgos y Trade-offs Inherentes

1. **Serialización Forzada y Rendimiento de Cuentas Calientes:**
   - *Riesgo:* Una cuenta sometida a 1,000 operaciones simultáneas se serializa estrictamente en la base de datos. Si cada transacción tarda $10\text{ ms}$ (debido a viajes de red JDBC y escrituras de log), el tiempo total para vaciar la cola es de 10 segundos.
   - *Trade-off Asumido:* Se prioriza la consistencia matemática absoluta sobre la latencia individual en cuentas disputadas.
2. **Saturación del Pool de Conexiones (HikariCP):**
   - *Riesgo:* Con un pool configurado en 25 conexiones (`maximum-pool-size: 25`), 1,000 hilos virtuales intentando abrir transacciones bloqueantes pueden retener conexiones físicas mientras esperan el lock en MySQL, provocando `SQLTransientConnectionException`.
   - *Mitigación:* Se implementó `innodb-lock-wait-timeout=15` en MySQL y reintentos exponenciales con jitter (`OperationExecutor`) para descongestionar el pool ante picos transitorios.

---

## 4. Limitaciones y Condiciones de Borde del Sistema

1. **Monolito de Base de Datos Única:**
   - La garantía transaccional ACID depende de residir en una única instancia de MySQL InnoDB. Si las cuentas pertenecieran a microservicios o shards independientes, la solución actual no aplicaría y requeriría una arquitectura Saga Orquestada con eventos de compensación y *Transactional Outbox*.
2. **Costo Asintótico en Auditoría del Ledger:**
   - La comprobación de invariante mediante `SUM(signed_amount)` sobre `ledger_entries` es una operación de complejidad $O(N)$ con respecto al histórico total de movimientos. En un entorno de producción a gran escala, requiere particionamiento temporal y puntos de control mensuales consolidados.
3. **Restricción de Concurrencia de Clave:**
   - La coordinación de idempotencia se delega a la restricción única `(client_id, idempotency_key)` en la tabla `operations`. Las solicitudes simultáneas con la misma clave esperan la confirmación de la primera transacción, respondiendo la misma salida una vez confirmada.
