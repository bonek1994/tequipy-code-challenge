package com.tequipy.challenge.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@Configuration
@EnableJpaRepositories(basePackages = ["com.tequipy.challenge.adapter.out.persistence.repository"])
class JpaConfig
