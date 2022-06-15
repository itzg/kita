package app.services;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Composes {@link AcmeRequestService} by abstracting away the account retrieval per issuer
 */
@Service
@Slf4j
public class AcmeRequestService {

    private final AcmeBaseRequestService baseRequestService;
    private final AcmeAccountService accountService;

    public AcmeRequestService(AcmeBaseRequestService baseRequestService,
        AcmeAccountService accountService
    ) {
        this.baseRequestService = baseRequestService;
        this.accountService = accountService;
    }

    @NonNull
    public <T> Mono<T> request(String issuerId, URI requestUrl, Object payload, Class<T> responseClass) {
        return accountService.accountForIssuer(issuerId)
            .flatMap(acmeAccount ->
                baseRequestService.request(issuerId, acmeAccount.jwk(), acmeAccount.accountUri().toString(), requestUrl, payload, responseClass)
                    .mapNotNull(HttpEntity::getBody));
    }

}
