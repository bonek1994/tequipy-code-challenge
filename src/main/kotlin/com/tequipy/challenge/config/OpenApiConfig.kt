package com.tequipy.challenge.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Tequipy Equipment Management API")
                .description(
                    "REST API for managing equipment inventory and allocation requests. " +
                    "Allocation processing is asynchronous and handled via RabbitMQ."
                )
                .version("1.0.0")
        )
}
