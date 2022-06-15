package app.model;

import lombok.Builder;

@Builder
public record Identifier(
    String type,
    String value
) {

    public static Identifier dns(String host) {
        return new Identifier("dns", host);
    }
}
