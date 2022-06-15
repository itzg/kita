package app.messages;

/**
 * @param csr Base64 encoding of CSR in DER (not PEM) format
 */
public record FinalizeRequest(
    String csr
) {

}
