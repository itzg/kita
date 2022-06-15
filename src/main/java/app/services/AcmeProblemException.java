package app.services;

import app.model.Problem;
import lombok.ToString;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@ToString
public class AcmeProblemException extends RuntimeException {

    private final Problem problem;
    private final WebClientResponseException clientException;

    public AcmeProblemException(Problem problem, WebClientResponseException clientException) {
        super(
            "ACME Server reported a problem with the request. Type=%s details=%s".formatted(
                problem.type(), problem.detail())
        );
        this.problem = problem;
        this.clientException = clientException;
    }

}
