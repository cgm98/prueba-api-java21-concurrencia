# Arquitectura del Sistema: Movimientos Financieros Concurrentes

Este documento describe la arquitectura, el diseño de componentes, las fronteras transaccionales y las garantías de integridad implementadas en la solución.

```mermaid
flowchart TD
    Client[Cliente HTTP / Pruebas Concurrentes] -->|POST /api/v1/withdrawals\nPOST /api/v1/transfers\nHeader: Idempotency-Key| API[MovementController\nValidación de Contrato RFC 7807]
    API -->|Ejecuta fuera de TX| Executor[OperationExecutor\nReintentos acotados + Jitter Backoff]
    Executor -->|Llama método @Transactional| Service[MovementService\nLímite Transaccional READ COMMITTED]
    
    subgraph TX[Transacción Atómica MySQL InnoDB]
        Service -->|1. Intenta reservar clave| OpRepo[OperationRepository\nINSERT ON DUPLICATE / UNIQUE KEY]
        OpRepo -->|Si ya existe| Replay[Recuperar resultado\nIdempotency-Replayed: true]
        OpRepo -->|Si es nueva| Lock[AccountRepository\nSELECT FOR UPDATE en orden ascendente de IDs]
        Lock --> Check{¿Saldo suficiente?}
        Check -->|No| Reject[Persistir REJECTED\nHTTP 422 sin asientos contables]
        Check -->|Sí| DebitCredit[Actualizar saldos\nDebitar origen / Acreditar destino]
        DebitCredit --> Ledger[LedgerRepository\nInsertar asientos inmutables]
        Ledger --> Complete[Actualizar Operation a APPLIED]
    end
    
    Complete -->|Commit atómico| SuccessResp[HTTP 201 Created\nLocation: /operations/{id}]
    Reject -->|Commit de rechazo| RejectResp[HTTP 422 Unprocessable Entity]
    Replay --> RepResp[HTTP 201 o 422\nIdempotency-Replayed: true]
```

---

## 1. Modelo de Datos y Restricciones (MySQL 8.4)

### Tabla `accounts`
Almacena las cuentas bancarias y sus saldos consolidados.
- `id` (VARCHAR(36), PK): UUID de la cuenta.
- `currency` (VARCHAR(3)): Moneda (ej. "PEN").
- `opening_balance` (DECIMAL(19, 2)): Saldo inicial verificado.
- `balance` (DECIMAL(19, 2)): Saldo actual.
- **Restricción de Integridad:** `CONSTRAINT chk_accounts_balance_positive CHECK (balance >= 0)`.

### Tabla `operations`
Garantiza idempotencia y audita el ciclo de vida de cada intención de movimiento.
- `id` (VARCHAR(36), PK): UUID de la operación.
- `client_id` (VARCHAR(64)): Cliente originador.
- `idempotency_key` (VARCHAR(64)): Clave de idempotencia única por cliente.
- `request_hash` (VARCHAR(64)): Hash SHA-256 canónico del payload.
- `type` (VARCHAR(20)): WITHDRAWAL o TRANSFER.
- `source_id` / `destination_id` (VARCHAR(36)): Cuentas involucradas.
- `amount` (DECIMAL(19, 2)): Monto del movimiento.
- `status` (VARCHAR(20)): PENDING, APPLIED o REJECTED.
- `result_code` (VARCHAR(50)): Código de resultado (ej. "OK", "INSUFFICIENT_FUNDS").
- `http_status` (INT): Código HTTP persistido (ej. 201, 422).
- `response_payload` (TEXT): JSON de la respuesta original.
- **Restricción de Unicidad:** `UNIQUE KEY uq_client_idempotency (client_id, idempotency_key)`.

### Tabla `ledger_entries`
Libro contable inmutable de partida doble donde cada movimiento genera registros permanentes.
- `id` (BIGINT, AUTO_INCREMENT, PK).
- `operation_id` (VARCHAR(36), FK -> `operations.id`).
- `account_id` (VARCHAR(36), FK -> `accounts.id`).
- `leg` (VARCHAR(20)): DEBIT o CREDIT.
- `signed_amount` (DECIMAL(19, 2)): Negativo para retiros/débitos, positivo para créditos.
- **Restricción de Unicidad:** `UNIQUE KEY uq_operation_leg (operation_id, leg)`.

---

## 2. Invariantes Financieras Demostrables

1. **Invariante de Balance de Cuenta:**
   $$\text{balance} = \text{opening\_balance} + \sum \text{signed\_amount}$$
2. **Invariante de Conservación en Transferencias:**
   $$\Delta \text{balance}(\text{origen}) + \Delta \text{balance}(\text{destino}) = 0$$
   $$\text{balance}(A) + \text{balance}(B) = \text{opening\_balance}(A) + \text{opening\_balance}(B)$$
3. **Invariante de Asientos por Operación:**
   - Para toda operación `WITHDRAWAL APPLIED`: exactamente 1 asiento contable con importe $-amount$.
   - Para toda operación `TRANSFER APPLIED`: exactamente 2 asientos contables (uno $-amount$ y otro $+amount$).
   - Para toda operación `REJECTED`: exactamente 0 asientos contables.
4. **Invariante de No Negatividad:**
   $$\forall \text{ cuenta } a: \text{balance}(a) \ge 0$$
