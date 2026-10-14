package com.library.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import com.library.catalog.messaging.InvalidEvent;
import com.library.catalog.messaging.LoanRequestCodec;

class LoanRequestCodecTest {
    private final LoanRequestCodec codec = new LoanRequestCodec(new JsonMapper());
    static String event() {
        return """
                {"eventId":"%s","eventType":"LoanRequested","schemaVersion":1,
                 "occurredAt":"2026-10-14T10:00:00Z","loanId":"%s","sagaId":"%s",
                 "correlationId":"%s","causationId":null,"payload":{"memberId":"%s",
                 "bookIds":["00000000-0000-0000-0000-000000000001"],"dueAt":"2026-10-15T10:00:00Z"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }
    static Stream<String> invalid() {
        String valid = event();
        return Stream.of("{", "null", "{}", valid.replace("LoanRequested", "UnknownEvent"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("[\"00000000-0000-0000-0000-000000000001\"]", "[]"),
                valid.replace("[\"00000000-0000-0000-0000-000000000001\"]", "[null]"),
                valid.replace("[\"00000000-0000-0000-0000-000000000001\"]", "[\"00000000-0000-0000-0000-000000000001\",\"00000000-0000-0000-0000-000000000001\"]"),
                valid.replace("2026-10-15T10:00:00Z", "2026-10-14T10:00:00Z"),
                valid.replace("00000000-0000-0000-0000-000000000001", "invalid-marker"),
                valid.replace("\"payload\":{", "\"payload\":null,\"ignoredPayload\":{"));
    }
    @ParameterizedTest
    @MethodSource("invalid")
    void rejectsInvalidEvents(String body) {
        assertThatThrownBy(() -> codec.decode(body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidEvent.class).hasMessageNotContaining("invalid-marker");
    }
    @Test
    void canonicalFingerprintIgnoresBookOrder() {
        String one = "00000000-0000-0000-0000-000000000001";
        String two = "00000000-0000-0000-0000-000000000002";
        String ordered = "[\"" + one + "\",\"" + two + "\"]";
        String reversed = "[\"" + two + "\",\"" + one + "\"]";
        String body = event().replace("[\"" + one + "\"]", ordered);
        var before = codec.decode(body.getBytes(StandardCharsets.UTF_8));
        var after = codec.decode(body.replace(ordered, reversed).getBytes(StandardCharsets.UTF_8));
        assertThat(after.requestHash()).isEqualTo(before.requestHash());
        assertThat(after.eventHash()).isEqualTo(before.eventHash());
        assertThat(after.books()).containsExactly(UUID.fromString(one), UUID.fromString(two));
    }

    @Test
    void ignoresUnknownFieldsAndWhitespaceInFingerprint() {
        String body = event();
        var before = codec.decode(body.getBytes(StandardCharsets.UTF_8));
        var after = codec.decode(body.replace("\"causationId\":null", "\"causationId\":null,\"futureField\":123")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(after.eventHash()).isEqualTo(before.eventHash());
        assertThat(after.requestHash()).isEqualTo(before.requestHash());
    }
}
