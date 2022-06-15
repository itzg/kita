package app.services;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class AcmeNonceService {

    private final WebClient webClient;
    private final AcmeDirectoryService directoryService;

    public AcmeNonceService(WebClient.Builder webClientBuilder, AcmeDirectoryService directoryService) {
        webClient = webClientBuilder.build();
        this.directoryService = directoryService;
    }
}
