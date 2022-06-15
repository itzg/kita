package app.model;

import java.net.URI;
import java.time.Instant;

/**
 *
 * @param type
 * @param url auth resource
 * @param token
 * @param status pending, processing, valid, invalid <a href="https://datatracker.ietf.org/doc/html/rfc8555#section-7.1.6">RFC</a>
 * @param validated
 * @param error
 */
public record Challenge(
    String type,
    URI url,
    String token,
    String status,
    Instant validated,
    Problem error
) {

    public static final String TYPE_HTTP_01 = "http-01";
}
