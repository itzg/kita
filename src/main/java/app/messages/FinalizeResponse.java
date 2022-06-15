package app.messages;

import app.model.Identifier;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Instant;
import java.util.List;

public record FinalizeResponse(
    String status,
    Instant expires,
    Instant notBefore,
    Instant notAfter,
    List<Identifier> identifiers,
    List<URI> authorizations,
    @JsonProperty("finalize")
    URI finalizeUri,
    URI certificate
) {

}
