package app.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;

public record Issuer(
    @NotNull
    URI directoryUrl,

    @NotEmpty
    List<@NotBlank String> emails,

    @AssertTrue
    boolean termsOfServiceAgreed
) {

}
