package app.services;

import app.config.AppProperties;
import app.messages.AuthzResponse;
import app.messages.CsrRequest;
import app.messages.FinalizeResponse;
import app.messages.OrderRequest;
import app.messages.OrderResponse;
import app.model.Challenge;
import app.model.Identifier;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Base64.Encoder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.security.auth.x500.X500Principal;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Service
@Slf4j
public class CertificateProcessingService {

    private final KubernetesClient k8s;
    private final AppProperties appProperties;
    private final AcmeDirectoryService directoryService;
    private final AcmeAccountService accountService;
    private final AcmeRequestService requestService;
    private final SolverService solverService;

    public CertificateProcessingService(KubernetesClient k8s,
        AppProperties appProperties,
        AcmeDirectoryService directoryService,
        AcmeAccountService accountService,
        AcmeRequestService requestService,
        SolverService solverService
    ) {
        this.k8s = k8s;
        this.appProperties = appProperties;
        this.directoryService = directoryService;
        this.accountService = accountService;
        this.requestService = requestService;
        this.solverService = solverService;
    }

    public Mono<Secret> initiateCertCreation(Ingress ingress, IngressTLS tls, String issuerId) {
        // https://datatracker.ietf.org/doc/html/rfc8555#section-4
        final List<String> hosts = tls.getHosts();

        final String ingressName = ingress.getMetadata().getName();
        final String secretName = tls.getSecretName();
        log.info("Initiating cert creation with issuer={} for tls entry with secret={} hosts={} in ingress={}",
            issuerId, secretName, tls.getHosts(), ingressName
        );

        final List<Identifier> identifiers = hosts.stream()
            .map(Identifier::dns)
            .toList();

        return requestService.request(issuerId, directoryService.directoryFor(issuerId).newOrder(),
                OrderRequest.builder()
                    .identifiers(identifiers)
                    .build(), OrderResponse.class
            )
            .flatMap(orderResponse -> {

                /*
                any authorization referenced in the "authorizations" array whose
                status is "pending" represents an authorization transaction that the
                client must complete before the server will issue the certificate
                (see Section 7.5)
                 */
                return Flux.fromIterable(orderResponse.authorizations())
                    .flatMap(authzUri -> loadAuthorization(issuerId, authzUri)
                        .flatMap(authz -> processAuthorization(issuerId, authzUri, authz, ingress))
                    )
                    .then(
                        submitCsr(issuerId, identifiers, orderResponse.finalizeUri())
                            .flatMap(csrResult ->
                                downloadCertChain(issuerId, csrResult.certificateUri())
                                    .map(certChain -> buildCertAndKey(certChain, csrResult.privateKey()))
                            )
                            .map(certAndKey -> storeSecret(issuerId, hosts, certAndKey.certChain(), certAndKey.privateKey(),
                                secretName, ingressName
                            ))
                    );
            });

    }

    private Secret storeSecret(String issuerId, List<String> hosts, String certChain, String privateKey, String secretName,
        String ingressName
    ) {
        final Encoder b64Encoder = Base64.getEncoder();
        final Secret secret = new SecretBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(secretName)
                .withLabels(Map.of(
                    Metadata.ISSUER_LABEL, issuerId,
                    Metadata.FOR_INGRESS_LABEL, ingressName
                ))
                .withAnnotations(Map.of(
                    Metadata.HOST_ANNOTATION, String.join(",", hosts)
                ))
                .build()
            )
            .withType("kubernetes.io/tls")
            .withData(Map.of(
                "tls.crt", b64Encoder.encodeToString(certChain.getBytes(StandardCharsets.UTF_8)),
                "tls.key", b64Encoder.encodeToString(privateKey.getBytes(StandardCharsets.UTF_8))
            ))
            .build();
        log.debug("Stored secret={}", secret.getMetadata().getName());

        return k8s.secrets()
            .createOrReplace(secret);
    }

    private CertAndKey buildCertAndKey(String certChain, PrivateKey privateKey) {
        final StringWriter keyPem = new StringWriter();
        final PemWriter pemWriter = new PemWriter(keyPem);
        try {
            pemWriter.writeObject(new PemObject(
                privateKey.getAlgorithm() + " PRIVATE KEY",
                privateKey.getEncoded()
            ));
            pemWriter.flush();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate PEM from private key", e);
        }
        return new CertAndKey(certChain, keyPem.toString());
    }

    record CertAndKey(
        String certChain,
        String privateKey
    ) {

    }

    private Mono<String> downloadCertChain(String issuerId, URI certificate) {
        return requestService.request(issuerId, certificate, "", String.class);
    }

    private Mono<CsrResult> submitCsr(String issuerId, List<Identifier> identifiers, URI finalizeUri) {
        log.debug("Submitting CSR to issuer={} with identifiers={}", issuerId, identifiers);

        final KeyPair keyPair = generateCertKeyPair();

        final JcaPKCS10CertificationRequestBuilder csrBuilder = new JcaPKCS10CertificationRequestBuilder(
            new X500Principal("CN=" + identifiers.get(0).value()), keyPair.getPublic());
        final Extensions extensions = createExtensions(identifiers);
        csrBuilder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions);

        final ContentSigner signer = createContentSigner(keyPair);

        final PKCS10CertificationRequest csr = csrBuilder.build(signer);

        final String encodedCsr = encodeCsr(csr);

        return requestService.request(issuerId, finalizeUri,
                CsrRequest.builder()
                    .csr(encodedCsr)
                    .build(),
                FinalizeResponse.class
            )
            .flatMap(finalizeResponse ->
                finalizeResponse.status().equals("valid") ?
                    Mono.just(
                        new CsrResult(finalizeResponse.certificate(), keyPair.getPrivate())
                    )
                    : Mono.error(new IllegalStateException("CSR submission wasn't valid"))
            );
    }

    record CsrResult(
        URI certificateUri,
        PrivateKey privateKey
    ) {

    }

    private String encodeCsr(PKCS10CertificationRequest csr) {
        final Encoder b64encoder = Base64.getUrlEncoder();
        final String encodedCsr;
        try {
            encodedCsr = b64encoder.encodeToString(csr.getEncoded());
        } catch (IOException e) {
            throw new RuntimeException("Trying to encode CSR to DER", e);
        }
        return encodedCsr;
    }

    private ContentSigner createContentSigner(KeyPair keyPair) {
        final JcaContentSignerBuilder signerBuilder = new JcaContentSignerBuilder("SHA256withRSA");
        final ContentSigner signer;
        try {
            signer = signerBuilder.build(keyPair.getPrivate());
        } catch (OperatorCreationException e) {
            throw new RuntimeException("Trying to create CSR signer", e);
        }
        return signer;
    }

    private Extensions createExtensions(List<Identifier> identifiers) {
        final ExtensionsGenerator extensionsGenerator;
        try {
            extensionsGenerator = new ExtensionsGenerator();
            extensionsGenerator.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                identifiers.stream()
                    .map(identifier -> new GeneralName(GeneralName.dNSName, identifier.value()))
                    .toArray(GeneralName[]::new)
            ));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create SAN extension", e);
        }
        return extensionsGenerator.generate();
    }

    private KeyPair generateCertKeyPair() {
        final KeyPairGenerator keyPairGenerator;
        try {
            keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Unable to find RSA key pair algorithm", e);
        }
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }

    private Mono<AuthzResponse> loadAuthorization(String issuerId, URI authzUri) {
        return requestService.request(issuerId, authzUri, "", AuthzResponse.class);
    }

    private Mono<AuthzResponse> processAuthorization(String issuerId, URI authzUri, AuthzResponse auth,
        Ingress appIngress
    ) {
        final Challenge httpChallenge = auth.challenges().stream()
            .filter(challenge -> challenge.type().equals(Challenge.TYPE_HTTP_01))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Unable to find " + Challenge.TYPE_HTTP_01 + " in challenges: " + auth.challenges()));
        final String token = httpChallenge.token();

        return accountService.buildKeyAuthorization(issuerId, token)
            .flatMap(keyAuthorization ->
                solverService.setupSolverIngress(issuerId, appIngress.getSpec().getIngressClassName(), auth.identifier().value(),
                    token, keyAuthorization
                ))
            .flatMap(ingressSetup ->
                // tell server we're ready for the challenge to be validated
                requestService.request(issuerId, httpChallenge.url(), "{}", Challenge.class)
                    .flatMap(resp -> {
                        log.debug("Challenge validation requested, resp={}", resp);
                        return Mono.fromFuture(ingressSetup.challengeCompleted());
                    })
                    .doOnNext(o -> log.debug("Challenge response completed"))
                    .flatMap(o -> pollUntilAuthFinalized(issuerId, authzUri))
                    .doOnTerminate(() -> solverService.removeSolverIngress(ingressSetup.ingress(), token))
            );
    }

    private Mono<AuthzResponse> pollUntilAuthFinalized(String issuerId, URI authzUri) {
        return loadAuthorization(issuerId, authzUri)
            .flatMap(resp -> {
                final String status = resp.status();
                log.debug("Polling for auth={}, got status={}", authzUri, status);
                if (Objects.equals(status, "pending")) {
                    // not an actual error, but drives the retry cycle
                    return Mono.error(AuthNotFinalized::new);
                } else {
                    return Mono.just(resp);
                }
            })
            .retryWhen(Retry.fixedDelay(appProperties.maxAuthFinalizeAttempts(), appProperties.authFinalizeRetryDelay())
                .filter(AuthNotFinalized.class::isInstance)
            );
    }

}
