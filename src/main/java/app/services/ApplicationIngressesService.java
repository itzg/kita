package app.services;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressList;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ApplicationIngressesService implements Closeable {

    private final KubernetesClient k8s;
    private final CertificateProcessingService certificateProcessingService;
    private final Watch ingressWatches;
    private final Watch tlsSecretWatches;
    private final Set<String/*ingress name*/> activeReconciles = Collections.synchronizedSet(new HashSet<>());

    public ApplicationIngressesService(KubernetesClient k8s, CertificateProcessingService certificateProcessingService) {
        this.k8s = k8s;
        this.certificateProcessingService = certificateProcessingService;

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

    @Scheduled(fixedDelayString = "#{@'app-app.config.AppProperties'.certRenewalCheckInterval}")
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

            final String requestedIssuerId = ingress.getMetadata().getLabels().get(Metadata.ISSUER_LABEL);
            if (tlsSecret == null) {
                initiateCertCreation(ingress, name, tls, requestedIssuerId);
            } else {
                final String tlsSecretIssuer = nullSafe(tlsSecret.getMetadata().getLabels()).get(Metadata.ISSUER_LABEL);
                if (!Objects.equals(tlsSecretIssuer, requestedIssuerId)) {
                    initiateCertCreation(ingress, name, tls, requestedIssuerId);
                } else {
                    // TODO is cert needing refresh
                    activeReconciles.remove(name);
                }
            }
        }

    }

    private void initiateCertCreation(Ingress ingress, String name, IngressTLS tls, String requestedIssuerId) {
        certificateProcessingService.initiateCertCreation(ingress, tls, requestedIssuerId)
            .subscribe(secret -> {
                    log.info("Cert creation complete for tls entry with secret={} hosts={} in ingress={}",
                        secret.getMetadata().getName(), tls.getHosts(), name
                    );
                },
                throwable -> {
                    log.warn("Problem while processing cert creation");
                },
                () -> activeReconciles.remove(name)
            );
    }

    @NonNull
    private Map<String, String> nullSafe(Map<String, String> value) {
        return value != null ? value : Map.of();
    }

    @Override
    public void close() throws IOException {
        ingressWatches.close();
        tlsSecretWatches.close();
    }
}
