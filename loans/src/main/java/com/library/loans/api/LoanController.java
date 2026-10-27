package com.library.loans.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.library.loans.api.dto.CreateLoanRequest;
import com.library.loans.api.dto.LoanResponse;
import com.library.loans.service.LoanService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LoanResponse create(
            @Valid @RequestBody CreateLoanRequest request) {
        return loanService.create(
                request.memberId(),
                request.registeredBy(),
                request.bookIds(),
                request.dueAt());
    }
    @org.springframework.web.bind.annotation.GetMapping("/{id}")
    public LoanResponse find(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) {
        return loanService.find(id);
    }

}