package app.services;

import app.config.Issuer;
import app.messages.AccountRequest;
import app.model.AcmeAccount;
import app.messages.AccountResponse;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class AcmeAccountService {

    private AcmeBaseRequestService baseRequestService;
    private final AcmeDirectoryService directoryService;
    private final Map<String, AcmeAccount> accounts = Collections.synchronizedMap(new HashMap<>());

    public AcmeAccountService(
        AcmeDirectoryService directoryService,
        AcmeBaseRequestService baseRequestService
    ) {
        this.directoryService = directoryService;
        this.baseRequestService = baseRequestService;
    }

    private static RSAKey generateJwk() {
        try {
            return new RSAKeyGenerator(2048)
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(UUID.randomUUID().toString())
                .generate();
        } catch (JOSEException e) {
            throw new RuntimeException(e);
        }
    }

    public Mono<AcmeAccount> accountForIssuer(String issuerId) {
        synchronized (accounts) {
            final AcmeAccount acmeAccount = accounts.get(issuerId);
            if (acmeAccount != null) {
                return Mono.just(acmeAccount);
            }

            log.debug("Retrieving account for issuerId={}", issuerId);

            final Issuer issuer = directoryService.issuerFor(issuerId);

            return retrieveAccount(issuerId, issuer)
                .doOnNext(account -> accounts.put(issuerId, account));
        }
    }

    private Mono<AcmeAccount> retrieveAccount(String issuerId, Issuer issuer) {
        final RSAKey jwk = generateJwk();

        final URI newAccountUrl = directoryService.directoryFor(issuerId).newAccount();

        return baseRequestService.request(issuerId,
                jwk, null,
                newAccountUrl,
                AccountRequest.builder()
                    .contact(issuer.emails().stream()
                        .map(email -> "mailto:" + email)
                        .toList()
                    )
                    .termsOfServiceAgreed(issuer.termsOfServiceAgreed())
                    .build(), AccountResponse.class
            )
            .map(entity -> {
                final AccountResponse response = entity.getBody();

                if (response != null) {
                    if (!Objects.equals(response.status(), "valid")) {
                        throw new IllegalStateException("Account is not valid, was " + response.status());
                    }

                    return AcmeAccount.builder()
                        .accountUri(entity.getHeaders().getLocation())
                        .jwk(jwk)
                        .build();
                } else {
                    throw new IllegalStateException("New account response was null");
                }
            })
            .doOnNext(account -> log.debug("Retrieved account={}", account.accountUri()));
    }

    /**
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc8555#section-8.1">RFC 8555 8.1</a>
     */
    public Mono<String> buildKeyAuthorization(String issuerId, String token) {
        return accountForIssuer(issuerId)
            .map(acmeAccount -> {
                try {
                    return token + "." + acmeAccount.jwk().computeThumbprint();
                } catch (JOSEException e) {
                    throw new RuntimeException("Trying to compute jwk thumbprint", e);
                }
            });
    }
}
