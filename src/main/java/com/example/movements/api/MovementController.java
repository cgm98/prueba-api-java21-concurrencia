package com.example.movements.api;

import com.example.movements.api.dto.OperationResponse;
import com.example.movements.api.dto.TransferRequest;
import com.example.movements.api.dto.WithdrawalRequest;
import com.example.movements.application.MovementCommand;
import com.example.movements.application.OperationExecutor;
import com.example.movements.domain.MovementResult;
import com.example.movements.domain.OperationStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Movimientos Financieros", description = "Operaciones transaccionales concurrentes de retiros y transferencias con idempotencia estricta")
public class MovementController {

    private final OperationExecutor operationExecutor;

    public MovementController(OperationExecutor operationExecutor) {
        this.operationExecutor = operationExecutor;
    }

    @PostMapping("/withdrawals")
    @Operation(summary = "Ejecutar retiro de fondos", description = "Debita fondos de una cuenta bancaria con bloqueo pesimista ordenado e idempotencia estricta.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Retiro aplicado exitosamente"),
            @ApiResponse(responseCode = "400", description = "Validación fallida de parámetros o UUID inválido"),
            @ApiResponse(responseCode = "404", description = "Cuenta bancaria no encontrada"),
            @ApiResponse(responseCode = "409", description = "Conflicto: Idempotency-Key reutilizada con payload diferente"),
            @ApiResponse(responseCode = "422", description = "Rechazo transaccional por fondos insuficientes"),
            @ApiResponse(responseCode = "503", description = "Servicio no disponible por alta contención de bloqueos (reintentable)")
    })
    public ResponseEntity<OperationResponse> withdraw(
            @Parameter(description = "Clave UUID de idempotencia obligatoria", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Parameter(description = "Identificador del cliente originador")
            @RequestHeader(value = "X-Client-Id", defaultValue = "default-client") String clientId,
            @Valid @RequestBody WithdrawalRequest request
    ) {
        validateIdempotencyKey(idempotencyKey);

        MovementCommand command = MovementCommand.withdrawal(
                request.accountId(),
                new BigDecimal(request.amount()),
                request.currency()
        );

        MovementResult result = operationExecutor.execute(clientId, idempotencyKey, command);
        return buildResponse(result);
    }

    @PostMapping("/transfers")
    @Operation(summary = "Ejecutar transferencia entre cuentas", description = "Transfiere fondos entre dos cuentas con bloqueo ordenado anti-deadlocks e idempotencia estricta.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transferencia aplicada exitosamente"),
            @ApiResponse(responseCode = "400", description = "Validación fallida, misma cuenta o monedas distintas"),
            @ApiResponse(responseCode = "404", description = "Cuenta origen o destino no encontrada"),
            @ApiResponse(responseCode = "409", description = "Conflicto: Idempotency-Key reutilizada con payload diferente"),
            @ApiResponse(responseCode = "422", description = "Rechazo transaccional por fondos insuficientes"),
            @ApiResponse(responseCode = "503", description = "Servicio no disponible por alta contención de bloqueos (reintentable)")
    })
    public ResponseEntity<OperationResponse> transfer(
            @Parameter(description = "Clave UUID de idempotencia obligatoria", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Parameter(description = "Identificador del cliente originador")
            @RequestHeader(value = "X-Client-Id", defaultValue = "default-client") String clientId,
            @Valid @RequestBody TransferRequest request
    ) {
        validateIdempotencyKey(idempotencyKey);

        MovementCommand command = MovementCommand.transfer(
                request.sourceAccountId(),
                request.destinationAccountId(),
                new BigDecimal(request.amount()),
                request.currency()
        );

        MovementResult result = operationExecutor.execute(clientId, idempotencyKey, command);
        return buildResponse(result);
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required and cannot be blank");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Idempotency-Key header must be a valid UUID string: " + key);
        }
    }

    private ResponseEntity<OperationResponse> buildResponse(MovementResult result) {
        URI location = URI.create("/api/v1/operations/" + result.operationId());
        int httpStatus = result.status() == OperationStatus.APPLIED ? 201 : 422;

        return ResponseEntity.status(httpStatus)
                .header("Location", location.toString())
                .header("Idempotency-Replayed", String.valueOf(result.replayed()))
                .body(OperationResponse.from(result));
    }
}
