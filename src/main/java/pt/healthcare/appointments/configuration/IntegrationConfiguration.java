package pt.healthcare.appointments.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationConfiguration {
  @Bean
  public ThreadPoolTaskScheduler integrationTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(3);
    scheduler.setThreadNamePrefix("integration-");
    scheduler.setAwaitTerminationSeconds(30);
    return scheduler;
  }
}
