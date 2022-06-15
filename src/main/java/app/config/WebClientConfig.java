package app.config;

import app.services.JwsMessageWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    private final ObjectMapper objectMapper;
    private AppProperties appProperties;

    public WebClientConfig(ObjectMapper objectMapper,
        AppProperties appProperties
    ) {
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    @Bean
    public WebClientCustomizer webClientCustomizer() {
        return webClientBuilder -> webClientBuilder
            .clientConnector(
                new ReactorClientHttpConnector(
                    HttpClient.create()
                        .responseTimeout(appProperties.responseTimeout())
                )
            )
            .exchangeStrategies(
                ExchangeStrategies.builder()
                    .codecs(clientCodecConfigurer -> {
                        clientCodecConfigurer.customCodecs().register(
                            new JwsMessageWriter(objectMapper)
                        );
                    })
                    .build()
            );
    }
}
