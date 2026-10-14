package com.library.catalog.books;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookService {
    public record Book(UUID id, String title, int totalCopies, int availableCopies) {}
    public static class BookNotFound extends RuntimeException {}

    private final JdbcTemplate jdbc;
    public BookService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Book create(String title, int copies) {
        if (title == null || title.isBlank() || title.trim().length() > 255 || copies < 0) {
            throw new IllegalArgumentException("Dados do título inválidos");
        }
        var book = new Book(UUID.randomUUID(), title.trim(), copies, copies);
        jdbc.update("insert into books(id, title, total_copies, available_copies) values (?, ?, ?, ?)",
                book.id(), book.title(), copies, copies);
        return book;
    }

    @Transactional(readOnly = true)
    public Book find(UUID id) {
        return jdbc.query("select * from books where id = ?", (rs, row) -> new Book(
                rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getInt("total_copies"), rs.getInt("available_copies")), id)
                .stream().findFirst().orElseThrow(BookNotFound::new);
    }
}
