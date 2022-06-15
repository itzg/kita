package app.config;

import java.net.URI;
import java.util.List;
import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

public record Issuer(
    @NotNull
    URI directoryUrl,

    @NotEmpty
    List<@NotBlank String> emails,

    @AssertTrue
    boolean termsOfServiceAgreed
) {

}
