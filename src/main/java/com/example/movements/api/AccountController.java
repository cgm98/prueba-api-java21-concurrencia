package com.example.movements.api;

import com.example.movements.api.dto.AccountResponse;
import com.example.movements.application.QueryService;
import com.example.movements.domain.Account;
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
@RequestMapping("/api/v1/accounts")
@Tag(name = "Cuentas Bancarias", description = "Consultas de saldo consolidado y datos de cuentas")
public class AccountController {

    private final QueryService queryService;

    public AccountController(QueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Consultar saldo de cuenta", description = "Obtiene el saldo confirmado actual y moneda de la cuenta bancaria especificada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cuenta encontrada exitosamente"),
            @ApiResponse(responseCode = "404", description = "Cuenta bancaria no encontrada")
    })
    public ResponseEntity<AccountResponse> getAccount(
            @Parameter(description = "UUID de la cuenta bancaria", required = true)
            @PathVariable UUID accountId
    ) {
        Account account = queryService.getAccount(accountId);
        return ResponseEntity.ok(AccountResponse.from(account));
    }
}
