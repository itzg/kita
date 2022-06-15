package app.services;

import app.model.Problem;
import app.model.SignableValue;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.URI;
import java.security.cert.X509Certificate;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class AcmeBaseRequestService {

    private final AcmeDirectoryService directoryService;
    private final WebClient webClient;

    public AcmeBaseRequestService(WebClient.Builder webClientBuilder, AcmeDirectoryService directoryService) {
        webClient = webClientBuilder
            .filter((request, next) -> {
                log.debug("Starting {} {}", request.method(), request.url());
                return next.exchange(request);
            })
            .build();
        this.directoryService = directoryService;
    }

    public <T> Mono<ResponseEntity<T>> request(String issuerId, RSAKey jwk, @Nullable String kid, URI requestUrl,
        Object payload, Class<T> responseClass
    ) {
        log.debug("Creating POST for issuerId={} to url={} payload={}", issuerId, requestUrl, payload);

        return preEntityRequest(issuerId, jwk, kid, requestUrl, payload, responseClass)
            .toEntity(responseClass)
            .doOnNext(directoryService.latchNonce(issuerId))
            .doOnNext(entity -> log.debug("Response status={} from url={} for issuerId={} body={}",
                entity.getStatusCode(), requestUrl, issuerId, entity.getBody()
            ));
    }

    @NotNull
    private <T> ResponseSpec preEntityRequest(String issuerId, RSAKey jwk, String kid, URI requestUrl, Object payload,
        Class<T> responseClass
    ) {
        return webClient.post()
            .uri(requestUrl)
            .contentType(JwsMessageWriter.JOSE_JSON)
            .body(
                directoryService.nonceForIssuer(issuerId)
                    .map(nonce -> SignableValue.builder()
                        .jwk(jwk)
                        .kid(kid)
                        .nonce(nonce)
                        .requestUrl(requestUrl)
                        .value(payload)
                        .build()
                    ), SignableValue.class
            )
            .retrieve()
            .onStatus(HttpStatus::isError, clientResponse ->
                clientResponse.bodyToMono(Problem.class)
                    .flatMap(problem -> clientResponse.createException()
                        .map(e -> new AcmeProblemException(problem, e))
                        .doOnNext(
                            e -> log.warn("Failed response from url={} for issuerId={} was problem={}", requestUrl, issuerId,
                                problem
                            ))
                    ));
    }

}
