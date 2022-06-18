package app.messages;

import static org.assertj.core.api.Assertions.assertThat;

import app.config.AppProperties;
import app.model.Identifier;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.test.context.TestPropertySource;

@JsonTest
@TestPropertySource("")
class OrderRequestTest {

    @Autowired
    private JacksonTester<OrderRequest> json;

    @Test
    void serializeWithTimeframe() throws IOException {
        final OrderRequest request = OrderRequest.builder()
            .identifiers(List.of(
                Identifier.builder()
                    .type("dns")
                    .value("example.com")
                    .build()
            ))
            .notBefore(Instant.ofEpochSecond(1654908327))
            .notAfter(Instant.ofEpochSecond(1654994727))
            .build();

        assertThat(json.write(request))
            .isEqualToJson("""
                {
                    "identifiers": [{"type":"dns","value":"example.com"}],
                    "notBefore": "2022-06-11T00:45:27Z",
                    "notAfter": "2022-06-12T00:45:27Z"
                }
                """, JSONCompareMode.STRICT);
    }

    @Test
    void serializeWithoutTimeframe() throws IOException {
        final OrderRequest request = OrderRequest.builder()
            .identifiers(List.of(
                Identifier.builder()
                    .type("dns")
                    .value("example.com")
                    .build()
            ))
            .build();

        assertThat(json.write(request))
            .isEqualToJson("""
                {
                    "identifiers": [{"type":"dns","value":"example.com"}]
                }
                """, JSONCompareMode.STRICT);
    }
}