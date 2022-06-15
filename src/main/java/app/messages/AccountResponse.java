package app.messages;

import java.net.URI;
import java.util.List;

/**
 *
 * @param status valid, deactivated, revoked
 * @param contact
 * @param orders
 */
public record AccountResponse(
    String status,
    List<String> contact,
    URI orders
) { }
