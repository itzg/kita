package app.model;

import com.nimbusds.jose.jwk.JWK;
import java.net.URI;
import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * @param jwk
 * @param kid set with the {@link AcmeAccount#accountUri()} after account creation
 * @param nonce
 * @param requestUrl
 * @param value
 */
@Builder
public record SignableValue(
    JWK jwk,
    @Nullable
    String kid,
    String nonce,
    URI requestUrl,
    @Nullable
    Object value
) {

}
