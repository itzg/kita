package app.services;

import app.model.SignableValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader.Builder;
import com.nimbusds.jose.JWSObjectJSON;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.codec.HttpMessageWriter;
import reactor.core.publisher.Mono;

@Slf4j
public class JwsMessageWriter implements HttpMessageWriter<SignableValue> {

    public static final MediaType JOSE_JSON = MediaType.parseMediaType("application/jose+json");
    /**
     * <a href="https://datatracker.ietf.org/doc/html/rfc8555#section-6.5">Replay Protection</a>
     */
    public static final String NONCE_SIGN_HEADER = "nonce";
    /**
     * <a href="https://datatracker.ietf.org/doc/html/rfc8555#section-6.4">Request URL Integrity</a>
     */
    public static final String URL_SIGN_HEADER = "url";

    private final ObjectMapper objectMapper;

    public JwsMessageWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @NotNull
    @Override
    public List<MediaType> getWritableMediaTypes() {
        return List.of(JOSE_JSON);
    }

    @Override
    public boolean canWrite(ResolvableType elementType, MediaType mediaType) {
        return JOSE_JSON.equals(mediaType);
    }

    @Override
    public Mono<Void> write(Publisher<? extends  SignableValue> inputStream, ResolvableType elementType, MediaType mediaType,
        ReactiveHttpOutputMessage message, Map<String, Object> hints) {

        return Mono.from(inputStream)
            .flatMap(signableValue -> {
                log.trace("Signing and serializing value={}", signableValue.value());
                try {
                    final Payload payload = signableValue.value() != null ?
                        signableValue.value() instanceof String s ?
                            new Payload(s)
                            : new Payload(objectMapper.writeValueAsBytes(signableValue.value()))
                        : new Payload("");

                    final JWSObjectJSON jwsObjectJSON = new JWSObjectJSON(payload);

                    final Builder headerBuilder = new Builder((JWSAlgorithm) signableValue.jwk().getAlgorithm())
                        .customParam(NONCE_SIGN_HEADER, signableValue.nonce())
                        .customParam(URL_SIGN_HEADER, signableValue.requestUrl().toString());
                    if (signableValue.kid() != null) {
                        log.trace("Using kid={} in signing header", signableValue.kid());
                        headerBuilder.keyID(signableValue.kid());
                    }
                    else {
                        headerBuilder.jwk(signableValue.jwk().toPublicJWK());
                    }
                    jwsObjectJSON.sign(
                        headerBuilder.build(),
                        new RSASSASigner((RSAKey) signableValue.jwk())
                    );

                    final String serialized = jwsObjectJSON.serializeFlattened();
                    log.trace("Serialized to JWS object={}", serialized);

                    final byte[] body = serialized.getBytes(StandardCharsets.UTF_8);
                    return message.writeWith(Mono.just(message.bufferFactory().wrap(body)));

                } catch (JsonProcessingException | JOSEException e) {
                    log.warn("Failed to sign/write the value={}", signableValue, e);
                    return Mono.error(new RuntimeException(e));
                }
            });
    }
}
