package dev.seatlock.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.net.URI;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> domain(ApiException error, HttpServletRequest request) {
        return problem(error.status(), error.code(), error.getMessage(), request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        String detail = error.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).sorted().findFirst().orElse("Invalid request");
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", detail, request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    ResponseEntity<ProblemDetail> malformed(Exception error, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Check the request body, path and required headers", request);
    }
    @ExceptionHandler(TransientDataAccessException.class)
    ResponseEntity<ProblemDetail> busy(TransientDataAccessException error, HttpServletRequest request) {
        var response = problem(HttpStatus.SERVICE_UNAVAILABLE, "RETRY_LATER", "Database busy; retry with the same idempotency key", request);
        return ResponseEntity.status(503).header("Retry-After", "1").body(response.getBody());
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException error, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "CONSTRAINT_CONFLICT", "The request conflicts with existing data", request);
    }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("urn:seatlock:error:" + code.toLowerCase(java.util.Locale.ROOT)));
        body.setProperty("code", code);
        body.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status).body(body);
    }
}
