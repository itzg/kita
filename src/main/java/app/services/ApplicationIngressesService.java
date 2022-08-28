package app.services;

import app.config.AppProperties;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressList;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class ApplicationIngressesService implements Closeable {

    private final KubernetesClient k8s;
    private final TaskScheduler taskScheduler;
    private final CertificateProcessingService certificateProcessingService;
    private final AppProperties appProperties;
    private final Watch ingressWatches;
    private final Watch tlsSecretWatches;
    private final Set<String/*ingress name*/> activeIngressReconciles = Collections.synchronizedSet(new HashSet<>());
    private final Map<String/*secretName*/, ScheduledFuture<?>> scheduledRenewals = new ConcurrentHashMap<>();

    public ApplicationIngressesService(KubernetesClient k8s,
        TaskScheduler taskScheduler,
        CertificateProcessingService certificateProcessingService,
        AppProperties appProperties
    ) {
        this.k8s = k8s;
        this.taskScheduler = taskScheduler;
        this.certificateProcessingService = certificateProcessingService;
        this.appProperties = appProperties;

        this.ingressWatches = setupIngressWatch();
        this.tlsSecretWatches = setupTlsSecretWatch();
    }

    private Watch setupIngressWatch() {
        return k8s.network().v1().ingresses()
            .withLabel(Metadata.ISSUER_LABEL)
            // ...but not solver ingress that we created temporarily
            .withLabelNotIn(Metadata.ROLE_LABEL, appProperties.solverRole())
            .watch(new Watcher<>() {
                @Override
                public void eventReceived(Action action, Ingress ingress) {
                    final String ingressName = ingress.getMetadata().getName();
                    log.debug("Observed event for ingress {}: {}", ingressName, action);

                    switch (action) {
                        case ADDED, MODIFIED -> reconcileIngress(ingress)
                            .subscribe(secret -> {
                            }, throwable ->
                                log.error("Issue while reconciling ingress={}", ingressName)
                            )
                        ;
                    }
                }

                @Override
                public void onClose(WatcherException cause) {

                }
            });
    }

    private Watch setupTlsSecretWatch() {
        return k8s.secrets()
            .withLabel(Metadata.ISSUER_LABEL)
            .watch(new Watcher<>() {
                @Override
                public void eventReceived(Action action, Secret resource) {
                    final String secretName = resource.getMetadata().getName();
                    log.debug("Observed event for secret {}: {}", secretName, action);

                    switch (action) {
                        case DELETED -> {
                            scheduledRenewals.remove(secretName);
                            checkCertRenewalsForSecret(secretName);
                        }
                        case ADDED, MODIFIED -> {
                            // NOTE: this will also take of scheduling renewal of
                            // TLS secrets we create/update

                            if (needsRenewal(resource)) {
                                // Would seem weird to get here if it's a new TLS secret;
                                // however, maybe a secret was created externally with
                                // an old cert.

                                checkCertRenewalsForSecret(secretName);
                            }
                        }
                    }
                }

                @Override
                public void onClose(WatcherException cause) {

                }
            });
    }

    public void checkCertRenewalsForSecret(@NonNull String secretName) {
        final IngressList ingresses = k8s.network().v1().ingresses()
            .withLabel(Metadata.ISSUER_LABEL)
            .list();

        Flux.fromStream(ingresses.getItems().stream()
                .filter(ingress -> ingress.getSpec().getTls().stream()
                    .anyMatch(ingressTLS -> Objects.equals(ingressTLS.getSecretName(), secretName)))
            )
            .flatMap(this::reconcileIngress)
            .subscribe(secret -> {
                }, throwable ->
                    log.error("Failed to process cert renewals for secret={}", secretName, throwable)
            );

    }

    private Flux<Secret> reconcileIngress(Ingress ingress) {
        final String name = ingress.getMetadata().getName();
        if (!activeIngressReconciles.add(name)) {
            // already being reconciled
            return Flux.empty();
        }

        return Flux.fromIterable(ingress.getSpec().getTls())
            .flatMap(tls -> processTlsSecret(ingress, tls))
            .doFinally(signalType -> {
                log.debug("Removing ingress={} from activeReconciles", name);
                activeIngressReconciles.remove(name);
            });
    }

    private Mono<Secret> processTlsSecret(Ingress ingress, IngressTLS tls) {
        final Secret tlsSecret = k8s.secrets()
            .withName(tls.getSecretName())
            .get();

        final String requestedIssuerId =
            appProperties.overrideIssuer() != null ?
                appProperties.overrideIssuer()
                : ingress.getMetadata().getLabels().get(Metadata.ISSUER_LABEL);

        if (tlsSecret == null) {
            return initiateCertCreation(ingress, tls, requestedIssuerId);
        } else {
            final String tlsSecretIssuer = nullSafe(tlsSecret.getMetadata().getLabels()).get(Metadata.ISSUER_LABEL);
            if (!Objects.equals(tlsSecretIssuer, requestedIssuerId)
                || needsRenewal(tlsSecret)) {
                return initiateCertCreation(ingress, tls, requestedIssuerId);
            } else {
                return Mono.empty();
            }
        }
    }

    /**
     * NOTE: if the secret is not due yet for renewal, a task will be scheduled to try at recommended renewal time.
     *
     * @param tlsSecret the TLS secret to check
     * @return true if due for renewal and cert creation should be initiated, false if not and a task was scheduled by this method
     */
    private boolean needsRenewal(Secret tlsSecret) {
        final String certContentEncoded = tlsSecret.getData().get("tls.crt");
        final String secretName = tlsSecret.getMetadata().getName();

        if (certContentEncoded != null) {
            final Decoder decoder = Base64.getDecoder();

            try (PemReader pemReader = new PemReader(new StringReader(
                new String(decoder.decode(certContentEncoded), StandardCharsets.UTF_8)
            ))) {
                final PemObject pemObject = pemReader.readPemObject();

                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                final X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(pemObject.getContent()));
                final Instant notAfter = cert.getNotAfter().toInstant();
                final Instant notBefore = cert.getNotBefore().toInstant();
                final Duration lifetime = Duration.between(notBefore,
                    // since it sets expiration just before and between's argument is exclusive
                    notAfter.plusSeconds(1)
                );

                // LetsEncrypt recommends renewing when there is a 3rd of lifetime left
                // https://letsencrypt.org/docs/integration-guide/#when-to-renew
                final Instant dueForRenewal = notAfter.minus(lifetime.dividedBy(3));
                if (Instant.now().isAfter(dueForRenewal)) {
                    log.info("TLS secret {} is due to be renewed since its lifetime is {} days and expires at {}",
                        secretName, lifetime.toDays(), notAfter
                    );
                    return true;
                } else {
                    scheduleRenewal(secretName, dueForRenewal);
                }
            } catch (IOException e) {
                log.error("Failed to read/close PEM reader", e);
            } catch (CertificateException e) {
                log.error("Failed to get X.509 cert factory", e);
            }
        } else {
            log.error("TLS secret {} is missing tls.crt data", secretName);
        }
        return false;
    }

    private void scheduleRenewal(String secretName, Instant dueForRenewal) {
        scheduledRenewals.compute(secretName, (name, oldScheduled) -> {
            if (oldScheduled != null) {
                oldScheduled.cancel(false);
            }
            log.info("Scheduling renewal of TLS secret {} at {}", secretName, dueForRenewal);
            return taskScheduler.schedule(() ->
                    checkCertRenewalsForSecret(secretName),
                dueForRenewal.plusSeconds(1)
            );
        });
    }

    private Mono<Secret> initiateCertCreation(Ingress ingress, IngressTLS tls, String requestedIssuerId) {
        final String ingressName = ingress.getMetadata().getName();
        if (appProperties.dryRun()) {
            log.info("Skipping cert creation of {} for ingress {} since dry-run is enabled",
                tls.getSecretName(), ingressName
            );
            return Mono.empty();
        }

        return certificateProcessingService.initiateCertCreation(ingress, tls, requestedIssuerId)
            .doOnSuccess(secret ->
                log.info("Cert creation complete for tls entry with secret={} hosts={} in ingress={}",
                    secret.getMetadata().getName(), tls.getHosts(), ingressName
                ))
            .doOnError(throwable ->
                log.warn("Problem while processing cert creation for ingress={} with tlsSecret={}",
                    ingressName, tls.getSecretName(), throwable
                )
            );
    }

    @NonNull
    private Map<String, String> nullSafe(Map<String, String> value) {
        return value != null ? value : Map.of();
    }

    @Override
    public void close() {
        ingressWatches.close();
        tlsSecretWatches.close();
    }
}
