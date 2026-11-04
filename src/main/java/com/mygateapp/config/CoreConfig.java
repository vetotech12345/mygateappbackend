package com.mygateapp.config;

import java.time.Clock;
import java.util.concurrent.Executor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class CoreConfig {

    /**
     * All business timestamps come from this clock (UTC, microsecond precision), so the server is the authority and
     * tests can control time.
     */
    @Bean
    public Clock clock() {
        return new MicrosecondClock(Clock.systemUTC());
    }

    /** Bounded pool for after-commit work such as push delivery. */
    @Bean(name = "applicationTaskExecutor")
    public Executor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("app-async-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /** Background jobs (invite expiry, push outbox, retention). Disabled in tests via app.jobs.enabled=false. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
