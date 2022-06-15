package app.messages;

import lombok.Builder;

@Builder
public record CsrRequest(
    String csr
) {

}
