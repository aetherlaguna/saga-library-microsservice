package com.library.loans.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import com.library.loans.domain.InvalidLoanException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidLoanException.class)
    public ProblemDetail handleInvalidLoan(
            InvalidLoanException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage());

        problem.setTitle("Empréstimo inválido");

        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException exception) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Revise os campos informados");

        problem.setTitle("Dados inválidos");

        var errors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", error.getDefaultMessage() == null
                                ? "Valor inválido"
                                : error.getDefaultMessage()))
                .toList();

        problem.setProperty("errors", errors);

        return problem;
    }
}