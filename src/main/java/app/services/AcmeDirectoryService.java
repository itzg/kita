package app.services;

import app.config.AppProperties;
import app.config.Issuer;
import app.model.AcmeDirectory;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class AcmeDirectoryService {
    public static final String NONCE_HEADER = "Replay-Nonce";

    private final AppProperties appProperties;
    private final Map<String /*issuer id*/, AcmeDirectory> directories;
    private final Map<String/*issuerId*/, String> latchedNonces = Collections.synchronizedMap(new HashMap<>());
    private final WebClient webClient;

    public AcmeDirectoryService(WebClient.Builder webClientBuilder, AppProperties appProperties) {
        this.appProperties = appProperties;
        webClient = webClientBuilder.build();
        // NOTE pre-emptively load directories to perform basic issuer validation during startup
        directories = loadDirectories(appProperties.issuers());
        log.debug("Loaded directories: {}", directories);
    }

    private Map<String, AcmeDirectory> loadDirectories(Map<String, Issuer> issuers) {
        log.debug("Loading directories for {}", issuers);
        return Flux.fromIterable(issuers.entrySet())
            .flatMap(
                entry ->
                    retrieveDirectory(webClient, entry.getValue().directoryUrl())
                        .map(acmeDirectory -> Map.entry(entry.getKey(), acmeDirectory)))
            .toStream()
            .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

    private Mono<AcmeDirectory> retrieveDirectory(WebClient webClient, URI directoryUrl) {
        return webClient
            .get()
            .uri(directoryUrl)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(AcmeDirectory.class);
    }

    /**
     * @throws IllegalStateException if issuer's directory not found
     */
    public AcmeDirectory directoryFor(String issuerId) {
        final AcmeDirectory directory = directories.get(issuerId);
        if (directory == null) {
            throw new IllegalStateException("Unable to find directory for provider " + issuerId);
        }
        return directory;
    }

    /**
     * @throws IllegalStateException if issuer is not configured
     */
    public Issuer issuerFor(String issuerId) {
        final Issuer issuer = appProperties.issuers().get(issuerId);
        if (issuer == null) {
            throw new IllegalArgumentException("Issuer is not configured: " + issuerId);
        }
        return issuer;
    }

    public Mono<String> nonceForIssuer(String issuerId) {
        synchronized (latchedNonces) {
            final String nonce = latchedNonces.remove(issuerId);
            if (nonce != null) {
                return Mono.just(nonce);
            }
        }
        return webClient.head()
            .uri(directoryFor(issuerId).newNonce())
            .retrieve()
            .toBodilessEntity()
            .mapNotNull(entity -> entity.getHeaders().getFirst(NONCE_HEADER));
    }

    /**
     * Call this method on a retrieved entity such as
     * {@snippet :
     *  webClient.post()
     *   // ...
     *   .retrieve()
     *   .toEntity(SomeResponse.class)
     *   .doOnNext(nonceService.latchNonce(issuerId))
     *}
     */
    public <T> Consumer<ResponseEntity<T>> latchNonce(String issuerId) {
        return entity ->
            latchedNonces.put(issuerId, entity.getHeaders().getFirst(NONCE_HEADER));
    }

}
