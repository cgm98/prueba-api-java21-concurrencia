# Entregable 2: Diagrama de Flujo de Retiro y Transferencia con Límite Transaccional

Este documento describe la arquitectura de ejecución, las fronteras de aislamiento y el flujo detallado de retiro y transferencia con sus límites transaccionales explícitos.

---

## 1. Diagrama Global del Flujo y Límite Transaccional

El siguiente diagrama detalla la interacción desde la recepción HTTP hasta la confirmación física en base de datos. Se distingue explícitamente el límite de la transacción local de Spring (`@Transactional` sobre MySQL InnoDB) frente a la capa de reintentos y controladores.

```mermaid
flowchart TD
    Client([Cliente HTTP / Test Suite]) -->|1. POST /withdrawals o /transfers\nHeader: Idempotency-Key| Ctrl[MovementController\nValidación RFC 7807 y Formato]

    subgraph NonTxBoundary["Capa Fuera de Transacción (Host JVM)"]
        Ctrl -->|2. Invoca fuera de TX| Exec[OperationExecutor\nGestor de Reintentos Exponenciales + Jitter]
    end

    subgraph TxBoundary["Límite Transaccional (@Transactional READ COMMITTED)"]
        Exec -->|3. executeAttempt| Svc[MovementService]
        
        Svc -->|4. tryReserve| OpRes{¿Clave Idempotente Existe?}
        OpRes -->|Sí: Clave Colisionada| CheckHash{¿Hash SHA-256 Coincide?}
        CheckHash -->|No: Payload Alterado| ConfErr[Lanzar IdempotencyConflictException\nAbortar TX -> HTTP 409]
        CheckHash -->|Sí: Reintento Idéntico| ReplayStored[Recuperar resultado previo\nSin tocar cuentas]

        OpRes -->|No: Clave Nueva| LockAccounts[AccountRepository\nSELECT ... FOR UPDATE]
        
        note1["Regla de Bloqueo Anti-Deadlocks:\nRetiro: Bloquea cuenta origen\nTransferencia: Bloquea min(idA, idB) y luego max(idA, idB)"]
        LockAccounts --- note1

        LockAccounts --> CheckFunds{¿Saldo Suficiente?}
        CheckFunds -->|No| RejectOp[1. UPDATE operations SET status='REJECTED', 422\n2. Sin asientos en ledger\n3. Confirmar TX Rechazo]
        
        CheckFunds -->|Sí| ApplyBalance[1. UPDATE accounts SET balance = balance +- amount\n2. INSERT INTO ledger_entries\n3. UPDATE operations SET status='APPLIED', 201]
    end

    ApplyBalance -->|5. COMMIT Atómico| RespOk([HTTP 201 Created\nLocation: /operations/id])
    RejectOp -->|5. COMMIT Rechazo| RespRej([HTTP 422 Unprocessable Entity])
    ReplayStored -->|Retorno fuera de TX| RespReplay([HTTP 201/422 con\nIdempotency-Replayed: true])
    ConfErr -->|Rollback| RespConflict([HTTP 409 Conflict])

    Exec -.->|Si captura Deadlock 1213 o Lock Timeout 1205| ExecRetry[Espera Backoff con Jitter\ny reintenta intento <= 3]
    ExecRetry -.-> Svc
```

---

## 2. Flujo Específico de Retiro de Fondos

En una operación de retiro (`POST /api/v1/withdrawals`), el límite transaccional involucra una sola cuenta bancaria:

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant M as MovementController
    participant E as OperationExecutor
    participant S as MovementService
    participant O as operations (MySQL)
    participant A as accounts (MySQL)
    participant L as ledger_entries (MySQL)

    C->>M: POST /api/v1/withdrawals (Idempotency-Key, accountId, amount, currency)
    M->>E: execute(clientId, key, command)
    E->>S: executeAttempt(...) [Inicia TX @Transactional]
    S->>O: INSERT INTO operations (status='PENDING', request_hash)
    S->>A: SELECT ... FROM accounts WHERE id = ? FOR UPDATE (X-Lock)
    alt Saldo < Monto
        S->>O: UPDATE operations SET status='REJECTED', result_code='INSUFFICIENT_FUNDS', http_status=422
        S-->>E: Commit TX (Rechazo Persistido)
        E-->>M: MovementResult (REJECTED)
        M-->>C: HTTP 422 Unprocessable Entity
    else Saldo >= Monto
        S->>A: UPDATE accounts SET balance = balance - amount WHERE id = ?
        S->>L: INSERT INTO ledger_entries (operation_id, account_id, leg='DEBIT', signed_amount=-amount)
        S->>O: UPDATE operations SET status='APPLIED', result_code='OK', http_status=201
        S-->>E: Commit TX (Éxito Atómico)
        E-->>M: MovementResult (APPLIED)
        M-->>C: HTTP 201 Created (Location: /operations/{id})
    end
```

---

## 3. Flujo Específico de Transferencia entre Cuentas

En una transferencia (`POST /api/v1/transfers`), el límite transaccional abarca la coordinación de dos cuentas garantizando la prevención de interbloqueos:

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant S as MovementService (@Transactional)
    participant A as accounts (InnoDB Locks)
    participant L as ledger_entries (Inmutable)
    participant O as operations (Auditoría)

    Note over S: 1. Ordenamiento Determinista:<br/>firstId = min(sourceId, destId)<br/>secondId = max(sourceId, destId)
    S->>A: SELECT ... WHERE id = firstId FOR UPDATE
    S->>A: SELECT ... WHERE id = secondId FOR UPDATE
    Note over S,A: Bloqueos adquiridos en orden ascendente lexicográfico (Anti-Deadlock)
    
    alt Saldo Origen < Monto
        S->>O: UPDATE operations SET status='REJECTED', http_status=422
        Note over S: Commit de rechazo (Cuentas y Ledger inalterados)
    else Saldo Origen >= Monto
        S->>A: UPDATE accounts SET balance = balance - amount WHERE id = sourceId
        S->>A: UPDATE accounts SET balance = balance + amount WHERE id = destId
        S->>L: INSERT INTO ledger_entries (leg='DEBIT', signed_amount=-amount)
        S->>L: INSERT INTO ledger_entries (leg='CREDIT', signed_amount=+amount)
        S->>O: UPDATE operations SET status='APPLIED', http_status=201
        Note over S: Commit Atómico (Ambas cuentas, 2 asientos y operación confirmados juntos)
    end
```

---

## 4. Garantías del Límite Transaccional

1. **Aislamiento `READ COMMITTED` + Locks Explícitos:** 
   - Evita lecturas sucias (*Dirty Reads*).
   - El bloqueo `FOR UPDATE` serializa a cualquier otra transacción que pretenda leer o escribir sobre la misma cuenta, eliminando actualizaciones perdidas (*Lost Updates*).
2. **Atomicidad Todo o Nada:**
   - Si la aplicación experimenta una desconexión o fallo en la mitad de la transferencia (por ejemplo, tras debitar el origen pero antes de acreditar el destino), el motor InnoDB revierte automáticamente todos los cambios del intento.
3. **Reintentos Fuera de Transacción:**
   - La clase `OperationExecutor` se sitúa estrictamente **fuera** de la demarcación transaccional. Ante un error transitorio de contención (`DeadlockLoserDataAccessException` o `CannotAcquireLockException`), la transacción fallida se descarta por completo y se reabre una nueva conexión y transacción con backoff exponencial.
