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
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ApplicationIngressesService implements Closeable {

    private final KubernetesClient k8s;
    private final CertificateProcessingService certificateProcessingService;
    private final AppProperties appProperties;
    private final Watch ingressWatches;
    private final Watch tlsSecretWatches;
    private final Set<String/*ingress name*/> activeReconciles = Collections.synchronizedSet(new HashSet<>());

    public ApplicationIngressesService(KubernetesClient k8s,
        CertificateProcessingService certificateProcessingService,
        AppProperties appProperties
    ) {
        this.k8s = k8s;
        this.certificateProcessingService = certificateProcessingService;
        this.appProperties = appProperties;

        this.ingressWatches = setupIngressWatch();
        this.tlsSecretWatches = setupTlsSecretWatch();
    }

    private Watch setupIngressWatch() {
        return k8s.network().v1().ingresses()
            .withLabel(Metadata.ISSUER_LABEL)
            .watch(new Watcher<>() {
                @Override
                public void eventReceived(Action action, Ingress ingress) {
                    switch (action) {
                        case ADDED, MODIFIED -> reconcileIngressTls(ingress);
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
                    if (action == Action.DELETED) {
                        checkCertRenewals();
                    }
                }

                @Override
                public void onClose(WatcherException cause) {

                }
            });
    }

    @Scheduled(
        // initial ingress listing will handle reconciling at startup, so delay for given interval
        initialDelayString = "#{@'kita-app.config.AppProperties'.certRenewalCheckInterval}",
        fixedDelayString = "#{@'kita-app.config.AppProperties'.certRenewalCheckInterval}"
    )
    public void checkCertRenewals() {
        final IngressList ingresses = k8s.network().v1().ingresses()
            .withLabel(Metadata.ISSUER_LABEL)
            .list();

        for (final Ingress ingress : ingresses.getItems()) {
            reconcileIngressTls(ingress);
        }
    }

    private void reconcileIngressTls(Ingress ingress) {
        final String name = ingress.getMetadata().getName();
        if (!activeReconciles.add(name)) {
            // already being reconciled
            return;
        }

        log.debug("Reconciling ingress={}", name);
        for (final IngressTLS tls : ingress.getSpec().getTls()) {

            final Secret tlsSecret = k8s.secrets()
                .withName(tls.getSecretName())
                .get();

            final String requestedIssuerId =
                appProperties.overrideIssuer() != null ?
                    appProperties.overrideIssuer()
                    : ingress.getMetadata().getLabels().get(Metadata.ISSUER_LABEL);

            if (tlsSecret == null) {
                initiateCertCreation(ingress, tls, requestedIssuerId);
            } else {
                final String tlsSecretIssuer = nullSafe(tlsSecret.getMetadata().getLabels()).get(Metadata.ISSUER_LABEL);
                if (!Objects.equals(tlsSecretIssuer, requestedIssuerId)
                    || needsRenewal(tlsSecret)) {
                    initiateCertCreation(ingress, tls, requestedIssuerId);
                } else {
                    activeReconciles.remove(name);
                }
            }
        }

    }

    private boolean needsRenewal(Secret tlsSecret) {
        final String certContentEncoded = tlsSecret.getData().get("tls.crt");
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
                if (Instant.now().isAfter(notAfter.minus(lifetime.dividedBy(3)))) {
                    log.info("TLS secret {} is due to be renewed since its lifetime is {} days and expires at {}",
                        tlsSecret.getMetadata().getName(), lifetime.toDays(), notAfter
                    );
                    return true;
                }
            } catch (IOException e) {
                log.error("Failed to read/close PEM reader", e);
            } catch (CertificateException e) {
                log.error("Failed to get X.509 cert factory", e);
            }
        } else {
            log.error("TLS secret {} is missing tls.crt data", tlsSecret.getMetadata().getName());
        }
        return false;
    }

    private void initiateCertCreation(Ingress ingress, IngressTLS tls, String requestedIssuerId) {
        final String ingressName = ingress.getMetadata().getName();
        if (appProperties.dryRun()) {
            log.info("Skipping cert creation of {} for ingress {} since dry-run is enabled",
                tls.getSecretName(), ingressName
            );
            return;
        }

        certificateProcessingService.initiateCertCreation(ingress, tls, requestedIssuerId)
            .subscribe(secret ->
                    log.info("Cert creation complete for tls entry with secret={} hosts={} in ingress={}",
                        secret.getMetadata().getName(), tls.getHosts(), ingressName
                    ),
                throwable -> log.warn("Problem while processing cert creation"),
                () -> activeReconciles.remove(ingressName)
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
