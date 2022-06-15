package app.messages;

import java.util.List;
import lombok.Builder;

@Builder
public record AccountRequest(
    List<String> contact,
    boolean termsOfServiceAgreed,
    boolean onlyReturnExisting
) {

}
