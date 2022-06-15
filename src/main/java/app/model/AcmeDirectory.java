package app.model;

import java.net.URI;

/**
 *
 * @param newNonce
 * @param newAccount
 * @param newOrder
 * @param newAuthz used for pre-authorization
 */
public record AcmeDirectory(
    URI newNonce,
    URI newAccount,
    URI newOrder,
    URI newAuthz
) {

}
