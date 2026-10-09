CREATE TABLE loans (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL,
    registered_by UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    due_at TIMESTAMPTZ NOT NULL,
    returned_at TIMESTAMPTZ,

    CONSTRAINT ck_loans_due_at
        CHECK (due_at >= created_at),

    CONSTRAINT ck_loans_returned_at
        CHECK (returned_at IS NULL OR returned_at >= created_at)
);

CREATE TABLE loan_books (
    loan_id UUID NOT NULL,
    book_id UUID NOT NULL,

    CONSTRAINT pk_loan_books
        PRIMARY KEY (loan_id, book_id),

    CONSTRAINT fk_loan_books_loan
        FOREIGN KEY (loan_id) REFERENCES loans (id)
);