package app.messages;

import app.model.Challenge;
import app.model.Identifier;
import java.time.Instant;
import java.util.List;

/**
 *
 * @param status pending, valid, invalid, revoked, deactivated, expired <a href="https://datatracker.ietf.org/doc/html/rfc8555#section-7.1.6">See</a>
 * @param expires
 * @param identifier
 * @param challenges
 */
public record AuthzResponse(
    String status,
    Instant expires,
    Identifier identifier,
    List<Challenge> challenges
) {

}
