package app.services;

import app.config.AppProperties;
import app.controllers.AcmeChallengeController;
import app.controllers.AcmeChallengeController.PreparedChallenge;
import io.fabric8.kubernetes.api.model.LoadBalancerIngress;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValueBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressSpecBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPort;
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPortBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.One;

@Service
@Slf4j
public class SolverService {

    private final KubernetesClient k8s;
    private final AcmeChallengeController acmeChallengeController;
    private final AppProperties appProperties;

    public SolverService(KubernetesClient k8s, AcmeChallengeController acmeChallengeController,
        AppProperties appProperties
    ) {
        this.k8s = k8s;
        this.acmeChallengeController = acmeChallengeController;
        this.appProperties = appProperties;
    }

    Mono<io.fabric8.kubernetes.api.model.Service> solverService() {
        final One<io.fabric8.kubernetes.api.model.Service> sink = Sinks.one();

        log.debug("Locating solver service resource with label {}={} via watch",
            Metadata.ROLE_LABEL, appProperties.solverRole()
        );

        //noinspection resource closed in mono below
        final Watch watch = k8s.services()
            .withLabel(Metadata.ROLE_LABEL, appProperties.solverRole())
            .watch(new Watcher<>() {

                @Override
                public void eventReceived(Action action, io.fabric8.kubernetes.api.model.Service service) {
                    if (action.equals(Action.ADDED) || action.equals(Action.MODIFIED)) {
                        log.debug("Located solver service named={}", service.getMetadata().getName());
                        sink.emitValue(service, (signalType, emitResult) -> {
                            log.error("Failure when emitting service={}", service);
                            return false;
                        });
                    }
                }

                @Override
                public void onClose(WatcherException cause) {
                    log.debug("Watch for solver service got closed", cause);
                    sink.emitError(cause, (signalType, emitResult) -> false);
                }
            });

        return sink.asMono()
            .doOnTerminate(watch::close);
    }

    public Mono<IngressSetup> setupSolverIngress(String issuerId, String ingressClassName, String host, String token,
        String keyAuthorization
    ) {
        return solverService()
            .flatMap(service -> {
                final PreparedChallenge preparedChallenge = acmeChallengeController.prepareForChallenge(token, keyAuthorization);

                final String ingressName = buildIngressName(service.getMetadata().getName(), host);

                final Ingress ingress = createSolverIngress(issuerId, ingressClassName, ingressName, host, service,
                    preparedChallenge
                );
                log.debug("Created ingress={} for solving challenge for host={}. Waiting for ingress to be ready...", ingressName,
                    host
                );

                return emitWhenIngressReady(ingress)
                    .map(readyIngress -> IngressSetup.builder()
                        .ingress(readyIngress)
                        .challengeCompleted(preparedChallenge.challengeCompleted())
                        .build());
            });
    }

    private Mono<Ingress> emitWhenIngressReady(Ingress ingress) {
        final One<Ingress> ingressReady = Sinks.one();

        //noinspection resource closed during mono below
        final Watch watch = k8s.network().v1().ingresses()
            .withName(ingress.getMetadata().getName())
            .watch(new Watcher<>() {
                @Override
                public void eventReceived(Action action, Ingress resource) {
                    final List<LoadBalancerIngress> lbIngresses = resource.getStatus().getLoadBalancer().getIngress();
                    log.trace("Event on ingress is action={} lbIngresses={}", action, lbIngresses);
                    if (lbIngresses != null && !lbIngresses.isEmpty()) {
                        log.debug("Solver ingress={} is ready", resource.getMetadata().getName());
                        ingressReady.emitValue(resource, (signalType, emitResult) -> false);
                    }
                }

                @Override
                public void onClose(WatcherException cause) {
                    log.debug("Watch of ingress readiness got closed", cause);
                    ingressReady.emitError(cause, (signalType, emitResult) -> false);
                }
            });

        return ingressReady.asMono()
            .doOnTerminate(watch::close);
    }

    private Ingress createSolverIngress(
        String issuerId, String ingressClassName, String ingressName, String host,
        io.fabric8.kubernetes.api.model.Service service, PreparedChallenge preparedChallenge
    ) {
        log.debug("Creating solver ingress={} with ingressClass={}", ingressName, ingressClassName);
        return k8s.network().v1().ingresses()
            .resource(
                new IngressBuilder()
                    .withMetadata(new ObjectMetaBuilder()
                        .withName(ingressName)
                        .withLabels(Map.of(
                            Metadata.ROLE_LABEL, appProperties.solverRole(),
                            Metadata.ISSUER_LABEL, issuerId
                        ))
                        .withAnnotations(Map.of(
                            Metadata.HOST_ANNOTATION, host
                        ))
                        .build()
                    )
                    .withSpec(new IngressSpecBuilder()
                        .withIngressClassName(ingressClassName)
                        .withRules(
                            new IngressRuleBuilder()
                                .withHost(host)
                                .withHttp(new HTTPIngressRuleValueBuilder()
                                    .withPaths(
                                        new HTTPIngressPathBuilder()
                                            .withPathType("Exact")
                                            .withPath(preparedChallenge.challengePath())
                                            .withBackend(new IngressBackendBuilder()
                                                .withService(new IngressServiceBackendBuilder()
                                                    .withName(service.getMetadata().getName())
                                                    .withPort(portForIngressFromService(service))
                                                    .build()
                                                )
                                                .build()
                                            )
                                            .build()
                                    )
                                    .build()
                                )
                                .build()
                        )
                        .build()
                    )
                    .build()
            )
            .createOrReplace();
    }

    private ServiceBackendPort portForIngressFromService(io.fabric8.kubernetes.api.model.Service service) {
        final String serviceName = service.getMetadata().getName();
        final List<ServicePort> ports = service.getSpec().getPorts();

        final ServiceBackendPortBuilder portBuilder = new ServiceBackendPortBuilder();
        if (ports == null || ports.isEmpty()) {
            throw new IllegalStateException("Missing service ports on service " + serviceName);
        } else if (ports.size() == 1) {
            setFromServicePort(portBuilder, ports.get(0));
        } else {
            final ServicePort servicePort = ports.stream()
                .filter(p -> Objects.equals(p.getName(), Metadata.SOLVER_SERVICE_PORT_NAME))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unable to pick out service port named %s from service %s".formatted(
                    Metadata.SOLVER_SERVICE_PORT_NAME, serviceName))
                );

            setFromServicePort(portBuilder, servicePort);
        }

        return portBuilder.build();
    }

    private void setFromServicePort(ServiceBackendPortBuilder builder, ServicePort servicePort) {
        if (servicePort.getName() != null) {
            builder.withName(servicePort.getName());
        } else {
            builder.withNumber(servicePort.getPort());
        }
    }

    private String buildIngressName(String serviceName, String host) {
        return serviceName + "-solver-" +
            host.replace('.', '-').toLowerCase();
    }

    public void removeSolverIngress(Ingress ingress, String token) {
        log.debug("Deleting solver ingress named={}", ingress.getMetadata().getName());
        k8s.network().v1().ingresses()
            .resource(ingress)
            .delete();
        acmeChallengeController.removeChallenge(token);
    }

    @Builder
    public record IngressSetup(
        Ingress ingress,
        CompletableFuture<?> challengeCompleted
    ) {

    }
}
