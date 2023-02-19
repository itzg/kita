package app.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param issuers                  one or more ACME issuers/providers, such as LetsEncrypt
 * @param responseTimeout          allowed response time when communicating with ACME issuer
 * @param authFinalize             configuration of the client polling after challenge observation as described in
 *                                 <a href="https://datatracker.ietf.org/doc/html/rfc8555#section-7.5.1">RFC 8555 Sec 7.5.1</a>
 * @param dryRun                   check for missing or expiring TLS secrets, but don't perform any issuing process
 * @param solverRole               configure the value of the {@value app.services.Metadata#ROLE_LABEL} service label to identify
 *                                 service that can solve (respond to) challenges
 * @param overrideIssuer           overrides the {@value app.services.Metadata#ISSUER_LABEL} ingress label with the issuer ID to
 *                                 use for all ingresses
 */
@ConfigurationProperties("kita")
@Validated
public record AppProperties(
    @NotEmpty
    Map<String, @Valid Issuer> issuers,

    @DefaultValue("10s") @NotNull
    Duration responseTimeout,

    @DefaultValue
    AuthFinalize authFinalize,

    boolean dryRun,

    @DefaultValue("solver") @NotBlank
    String solverRole,

    String overrideIssuer
) {

    /**
     * @param maxAttempts
     * @param pollDelay amount of delay between polls of the server's status
     */
    public record AuthFinalize(
        @DefaultValue("60") @Min(1)
        long maxAttempts,

        @DefaultValue("2s") @NotNull
        Duration pollDelay
    ) {

    }
}
