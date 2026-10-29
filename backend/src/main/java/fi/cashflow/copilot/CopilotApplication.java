package fi.cashflow.copilot;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CopilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(CopilotApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.system(java.time.ZoneId.of("Europe/Helsinki"));
    }
}
