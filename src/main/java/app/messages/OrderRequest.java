package app.messages;

import app.model.Identifier;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

@Builder
@JsonInclude(Include.NON_NULL)
public record OrderRequest(
    List<Identifier> identifiers,
    Instant notBefore,
    Instant notAfter
) {

}
