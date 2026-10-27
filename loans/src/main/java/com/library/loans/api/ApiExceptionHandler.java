package com.library.loans.api;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
import com.library.loans.domain.InvalidLoanException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Corpo da requisição inválido ou malformado");
        problem.setTitle("Requisição inválida");
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleConcurrentUpdate(OptimisticLockingFailureException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "O empréstimo foi alterado por outra operação; recarregue e tente novamente");
        problem.setTitle("Conflito de atualização");
        return problem;
    }

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
    @ExceptionHandler(com.library.loans.service.LoanNotFound.class)
    public ProblemDetail handleNotFound(com.library.loans.service.LoanNotFound exception) {
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,exception.getMessage());
        problem.setTitle("Recurso não encontrado");
        return problem;
    }

}