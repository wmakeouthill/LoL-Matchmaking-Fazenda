package br.com.lolmatchmaking.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String MESSAGE_KEY = "message";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        Map<String, Object> response = createErrorResponse(
                "Erro de validação",
                HttpStatus.BAD_REQUEST.value(),
                errors);

        log.warn("Erro de validação: {}", errors);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolationException(
            ConstraintViolationException ex) {

        Map<String, String> errors = new HashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String fieldName = violation.getPropertyPath().toString();
            String errorMessage = violation.getMessage();
            errors.put(fieldName, errorMessage);
        }

        Map<String, Object> response = createErrorResponse(
                "Erro de validação de parâmetros",
                HttpStatus.BAD_REQUEST.value(),
                errors);

        log.warn("Erro de validação de constraint: {}", errors);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatchException(
            MethodArgumentTypeMismatchException ex) {

        String error = String.format("Parâmetro '%s' deve ser do tipo %s",
                ex.getName(),
                ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "desconhecido");

        Map<String, Object> response = createErrorResponse(
                "Erro de tipo de parâmetro",
                HttpStatus.BAD_REQUEST.value(),
                Map.of("parameter", error));

        log.warn("Erro de tipo de parâmetro: {}", error);
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(
            IllegalArgumentException ex) {

        Map<String, Object> response = createErrorResponse(
                "Argumento inválido",
                HttpStatus.BAD_REQUEST.value(),
                Map.of(MESSAGE_KEY, ex.getMessage()));

        log.warn("Argumento inválido: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalStateException(
            IllegalStateException ex) {

        Map<String, Object> response = createErrorResponse(
                "Estado inválido",
                HttpStatus.CONFLICT.value(),
                Map.of(MESSAGE_KEY, ex.getMessage()));

        log.warn("Estado inválido: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(MatchCreationException.class)
    public ResponseEntity<Map<String, Object>> handleMatchCreationException(
            MatchCreationException ex) {

        Map<String, Object> response = createErrorResponse(
                "Erro ao criar partida",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                Map.of(MESSAGE_KEY, ex.getMessage()));

        log.error("Erro ao criar partida", ex);
        return ResponseEntity.internalServerError().body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {

        Map<String, Object> response = createErrorResponse(
                "Erro interno do servidor",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                Map.of(MESSAGE_KEY, "Ocorreu um erro inesperado"));

        log.error("Erro não tratado", ex);
        return ResponseEntity.internalServerError().body(response);
    }

    private Map<String, Object> createErrorResponse(String title, int status, Object details) {
        Map<String, Object> response = new HashMap<>();
        response.put("title", title);
        response.put("status", status);
        response.put("timestamp", Instant.now());
        response.put("details", details);
        return response;
    }
}
