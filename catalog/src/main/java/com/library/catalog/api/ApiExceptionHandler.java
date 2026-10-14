package com.library.catalog.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.library.catalog.books.BookService;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BookService.BookNotFound.class)
    public ProblemDetail missing() {
        return problem(HttpStatus.NOT_FOUND, "Título não encontrado", "O título solicitado não foi encontrado");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail unreadable() {
        return problem(HttpStatus.BAD_REQUEST, "Requisição inválida", "Corpo ou identificador inválido");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException failure) {
        var result = problem(HttpStatus.BAD_REQUEST, "Dados inválidos", "Revise os campos informados");
        result.setProperty("errors", failure.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message",
                        error.getDefaultMessage() == null ? "Valor inválido" : error.getDefaultMessage())).toList());
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid() {
        return problem(HttpStatus.BAD_REQUEST, "Dados inválidos", "Revise os campos informados");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        var result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setTitle(title);
        return result;
    }
}
