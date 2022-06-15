package app.messages;

import app.model.Identifier;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 *
 * @param status pending, ready, processing, valid <a href="https://datatracker.ietf.org/doc/html/rfc8555#section-7.1.6">See</a>
 * @param expires
 * @param notBefore
 * @param notAfter
 * @param identifiers
 * @param authorizations
 * @param finalizeUri
 */
public record OrderResponse(
    String status,
    Instant expires,
    Instant notBefore,
    Instant notAfter,
    List<Identifier> identifiers,
    List<URI> authorizations,
    @JsonProperty("finalize")
    URI finalizeUri
) {

}
