package com.library.loans.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.loans.domain.Loan;

class ConcurrentUpdateHttpTest {
    private static final UUID ID = UUID.randomUUID();

    @Test
    void returnsConflictWithoutExposingPersistenceDetails() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ConflictingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflito de atualização"))
                .andExpect(jsonPath("$.detail").value(
                        "O empréstimo foi alterado por outra operação; recarregue e tente novamente"))
                .andExpect(content().string(not(containsString(ID.toString()))))
                .andExpect(content().string(not(containsString("com.library"))))
                .andExpect(content().string(not(containsString("Loan"))));
    }

    @RestController
    static class ConflictingController {
        @GetMapping("/test/conflict")
        void conflict() {
            throw new ObjectOptimisticLockingFailureException(Loan.class, ID);
        }
    }
}
