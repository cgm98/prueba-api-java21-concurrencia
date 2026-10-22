package com.example.movements.api;

import com.example.movements.domain.exception.AccountNotFoundException;
import com.example.movements.domain.exception.ConcurrencyRetryExhaustedException;
import com.example.movements.domain.exception.CurrencyMismatchException;
import com.example.movements.domain.exception.IdempotencyConflictException;
import com.example.movements.domain.exception.InvalidAmountException;
import com.example.movements.domain.exception.SameAccountTransferException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed"
        );
        problem.setType(URI.create("urn:problem:validation-error"));
        problem.setTitle("Validation Error");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("retryable", false);

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );
        problem.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Required header missing: " + ex.getHeaderName()
        );
        problem.setType(URI.create("urn:problem:validation-error"));
        problem.setTitle("Missing Header");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("retryable", false);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler({IllegalArgumentException.class, InvalidAmountException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problem.setType(URI.create("urn:problem:validation-error"));
        problem.setTitle("Bad Request");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("retryable", false);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(SameAccountTransferException.class)
    public ResponseEntity<ProblemDetail> handleSameAccount(SameAccountTransferException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problem.setType(URI.create("urn:problem:same-account"));
        problem.setTitle("Same Account Error");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "SAME_ACCOUNT");
        problem.setProperty("retryable", false);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<ProblemDetail> handleCurrencyMismatch(CurrencyMismatchException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                ex.getMessage()
        );
        problem.setType(URI.create("urn:problem:currency-mismatch"));
        problem.setTitle("Currency Mismatch");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "VALIDATION_ERROR");
        problem.setProperty("retryable", false);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAccountNotFound(AccountNotFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
        problem.setType(URI.create("urn:problem:account-not-found"));
        problem.setTitle("Account Not Found");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "ACCOUNT_NOT_FOUND");
        problem.setProperty("retryable", false);

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        log.warn("Alerta de Conflicto de Idempotencia en {}: clave={}, operacionExistente={}",
                request.getRequestURI(), ex.getIdempotencyKey(), ex.getExistingOperationId());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "La clave idempotente ya ha sido procesada previamente con un payload diferente. " +
                "Por seguridad e integridad transaccional, no es posible reutilizar una clave existente con diferente cuerpo. " +
                "Genere un nuevo Idempotency-Key para esta operación."
        );
        problem.setType(URI.create("urn:problem:idempotency-key-reused"));
        problem.setTitle("Clave idempotente reutilizada con diferente payload");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "IDEMPOTENCY_KEY_REUSED");
        problem.setProperty("retryable", false);
        problem.setProperty("idempotencyKey", ex.getIdempotencyKey());
        if (ex.getExistingOperationId() != null) {
            problem.setProperty("existingOperationId", ex.getExistingOperationId().toString());
        }
        if (ex.getExistingStatus() != null) {
            problem.setProperty("existingStatus", ex.getExistingStatus().name());
        }
        problem.setProperty("securityWarning", "IDEMPOTENCY_PAYLOAD_TAMPERING_BLOCKED");

        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ConcurrencyRetryExhaustedException.class)
    public ResponseEntity<ProblemDetail> handleConcurrencyExhausted(ConcurrencyRetryExhaustedException ex, HttpServletRequest request) {
        log.warn("Concurrency retries exhausted on {}", request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Se agotaron los reintentos automáticos por alta contención de bloqueos. Repita la solicitud con la misma clave."
        );
        problem.setType(URI.create("urn:problem:concurrency-retry-exhausted"));
        problem.setTitle("Concurrency Retries Exhausted");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "CONCURRENCY_RETRY_EXHAUSTED");
        problem.setProperty("retryable", true);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(problem);
    }

    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ProblemDetail> handleCannotAcquireLock(CannotAcquireLockException ex, HttpServletRequest request) {
        log.warn("Cannot acquire lock on {}", request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Bloqueo no disponible temporalmente. Intente nuevamente."
        );
        problem.setType(URI.create("urn:problem:database-unavailable"));
        problem.setTitle("Database Unavailable");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "DATABASE_UNAVAILABLE");
        problem.setProperty("retryable", true);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled internal server error on {}", request.getRequestURI(), ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected internal error occurred."
        );
        problem.setType(URI.create("urn:problem:internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("retryable", false);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
