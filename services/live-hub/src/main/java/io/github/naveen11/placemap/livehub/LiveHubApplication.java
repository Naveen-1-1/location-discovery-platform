package io.github.naveen11.placemap.livehub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LiveHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveHubApplication.class, args);
    }
}
