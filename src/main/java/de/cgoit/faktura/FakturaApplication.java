package de.cgoit.faktura;

import de.cgoit.faktura.shared.DevProfileGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

@SpringBootApplication
public class FakturaApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(FakturaApplication.class);
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                DevProfileGuard.check(event.getEnvironment()));
        application.run(args);
    }
}
