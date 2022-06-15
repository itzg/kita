package app.controllers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AcmeChallengeController.BASE_CHALLENGE_PATH)
@Slf4j
public class AcmeChallengeController {

    public static final String BASE_CHALLENGE_PATH = "/.well-known/acme-challenge";

    private final Map<String/*token*/, PendingChallenge> pendingChallenges = Collections.synchronizedMap(new HashMap<>());

    public PreparedChallenge prepareForChallenge(String token, String keyAuthorization) {
        log.debug("Preparing for challenge of token={}", token);
        final PendingChallenge pendingChallenge = PendingChallenge.builder()
            .challengeCompleted(new CompletableFuture<>())
            .keyAuthorization(keyAuthorization)
            .build();
        pendingChallenges.put(token, pendingChallenge);
        return PreparedChallenge.builder()
            .challengeCompleted(pendingChallenge.challengeCompleted())
            .challengePath(challengePathForToken(token))
            .build();
    }

    public void removeChallenge(String token) {
        log.debug("Removing challenge for token={}", token);
        pendingChallenges.remove(token);
    }

    protected String challengePathForToken(String token) {
        return BASE_CHALLENGE_PATH + "/" + token;
    }

    @GetMapping(value = "{token}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
    public String handleChallenge(@PathVariable String token) {
        log.debug("Processing challenge request for token={}", token);
        final PendingChallenge pendingChallenge = pendingChallenges.get(token);
        if (pendingChallenge != null) {

            pendingChallenge.challengeCompleted().complete(token);

            log.debug("Responding with key authorization for token={}", token);
            return pendingChallenge.keyAuthorization();
        }
        else {
            log.warn("Challenge for token={} did not exist", token);
            throw new ChallengeNotPresent(token);
        }
    }

    @Builder
    private record PendingChallenge(
        String keyAuthorization,
        CompletableFuture<String> challengeCompleted
    ) {}

    @Builder
    public record PreparedChallenge(
        CompletableFuture<?> challengeCompleted,
        String challengePath
    ) {}
}
