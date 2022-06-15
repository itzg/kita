package app;

import app.config.AppProperties;
import java.security.Security;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.config.EnableWebFlux;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class K8sIngressTlsAcmeApplication {

    public static void main(String[] args) {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        SpringApplication.run(K8sIngressTlsAcmeApplication.class, args);
    }

}
