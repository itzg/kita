package app.config;

import java.time.Duration;
import java.util.Map;
import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("app")
@Validated
public record AppProperties(
    @NotEmpty
    Map<String, @Valid Issuer> issuers,

    @DefaultValue("10s") @NotNull
    Duration responseTimeout,

    @DefaultValue("6h") @NotNull
    Duration certRenewalCheckInterval,

    @DefaultValue("60") @Min(1)
    long maxAuthFinalizeAttempts,

    @DefaultValue("2s") @NotNull
    Duration authFinalizeRetryDelay
) {

}
