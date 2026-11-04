package com.mygateapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Each module keeps its repositories in its own {@code repository} package (e.g. {@code guards.repository}). */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.mygateapp")
public class JpaConfig {
}
