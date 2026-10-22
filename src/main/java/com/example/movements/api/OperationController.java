package com.example.movements.api;

import com.example.movements.api.dto.OperationResponse;
import com.example.movements.application.QueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/operations")
@Tag(name = "Operaciones Transaccionales", description = "Consultas de auditoría e historial del libro mayor y estado de operaciones")
public class OperationController {

    private final QueryService queryService;

    public OperationController(QueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{operationId}")
    @Operation(summary = "Consultar estado de operación", description = "Obtiene el registro persistido de una operación de retiro o transferencia, incluyendo estado, resultado y marcas de tiempo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Operación encontrada exitosamente"),
            @ApiResponse(responseCode = "404", description = "Operación no encontrada")
    })
    public ResponseEntity<OperationResponse> getOperation(
            @Parameter(description = "UUID de la operación", required = true)
            @PathVariable UUID operationId
    ) {
        return queryService.getOperation(operationId)
                .map(OperationResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
