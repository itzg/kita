package app.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ChallengeNotPresent extends RuntimeException {

    private final String token;

    public ChallengeNotPresent(String token) {
        super("Challenge not present for token " + token);
        this.token = token;
    }

    public String getToken() {
        return token;
    }
}
