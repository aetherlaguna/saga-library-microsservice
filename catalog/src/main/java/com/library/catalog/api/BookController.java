package com.library.catalog.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.library.catalog.books.BookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/books")
public class BookController {
    public record CreateBook(@NotBlank @Size(max = 255) String title,
            @NotNull @PositiveOrZero Integer totalCopies) {}
    private final BookService books;
    public BookController(BookService books) { this.books = books; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookService.Book create(@Valid @RequestBody CreateBook request) {
        return books.create(request.title(), request.totalCopies());
    }

    @GetMapping("/{id}")
    public BookService.Book get(@PathVariable UUID id) { return books.find(id); }
}
