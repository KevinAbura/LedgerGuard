package com.kevinabura.ledgerguard.api;

import com.kevinabura.ledgerguard.error.ConflictException;
import com.kevinabura.ledgerguard.error.InsufficientFundsException;
import com.kevinabura.ledgerguard.error.LedgerException;
import com.kevinabura.ledgerguard.error.NotFoundException;
import com.kevinabura.ledgerguard.error.UnbalancedTransactionException;
import com.kevinabura.ledgerguard.error.ValidationException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception);
    }

    @ExceptionHandler({
        ConflictException.class,
        InsufficientFundsException.class,
        UnbalancedTransactionException.class
    })
    ResponseEntity<ApiError> conflict(LedgerException exception) {
        return error(HttpStatus.CONFLICT, exception);
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> validation(ValidationException exception) {
        return error(HttpStatus.BAD_REQUEST, exception);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> malformed(Exception exception) {
        return new ResponseEntity<>(
                new ApiError("invalid_request", "Required header or request body is invalid", Instant.now()),
                HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, LedgerException exception) {
        return new ResponseEntity<>(
                new ApiError(exception.code(), exception.getMessage(), Instant.now()), status);
    }

    record ApiError(String code, String message, Instant timestamp) {}
}
