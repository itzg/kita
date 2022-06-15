package app.model;

import com.nimbusds.jose.jwk.RSAKey;
import java.net.URI;
import java.util.List;
import lombok.Builder;

@Builder
public record AcmeAccount(
    URI accountUri,
    RSAKey jwk
    ) {

}
