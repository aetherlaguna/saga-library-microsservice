package com.library.loans;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import com.library.loans.messaging.*;

class CatalogResultCodecTest {
    private final CatalogResultCodec codec = new CatalogResultCodec(new JsonMapper());
    static String valid() {
        return """
                {"eventId":"%s","eventType":"BooksReserved","schemaVersion":1,"occurredAt":"2026-10-26T10:00:00Z",
                "loanId":"%s","sagaId":"%s","correlationId":"%s","causationId":"%s",
                "payload":{"reservationId":"%s","bookIds":["00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002"]}}
                """.formatted(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
    }
    static Stream<String> invalid() {
        String valid=valid();
        return Stream.of("{","null","{}",valid.replace("BooksReserved","Unknown"),
                valid.replace("\"schemaVersion\":1","\"schemaVersion\":2"),
                valid.replace("\"bookIds\":[","\"bookIds\":[null,"),
                valid.replace("00000000-0000-0000-0000-000000000002","00000000-0000-0000-0000-000000000001"));
    }
    @ParameterizedTest @MethodSource("invalid") void rejectsMalformedEvents(String body) {
        assertThatThrownBy(() -> codec.decode(body.getBytes(StandardCharsets.UTF_8))).isInstanceOf(InvalidCatalogResult.class);
    }
    @Test void ignoresUnknownFieldsAndOrderWhilePreservingIdentity() {
        String body=valid();
        var one=codec.decode(body.getBytes(StandardCharsets.UTF_8));
        String changed=body.replace("\"schemaVersion\":1","\"future\":123,\"schemaVersion\":1")
                .replace("00000000-0000-0000-0000-000000000001","TEMP")
                .replace("00000000-0000-0000-0000-000000000002","00000000-0000-0000-0000-000000000001")
                .replace("TEMP","00000000-0000-0000-0000-000000000002");
        assertThat(codec.decode(changed.getBytes(StandardCharsets.UTF_8)).eventHash()).isEqualTo(one.eventHash());
    }
}
