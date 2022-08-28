package app;

import java.security.Security;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
//@EnableConfigurationProperties(AppProperties.class)
public class K8sIngressTlsAcmeApplication {

    public static void main(String[] args) {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        SpringApplication.run(K8sIngressTlsAcmeApplication.class, args);
    }

}
