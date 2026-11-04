package com.mygateapp.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI myGateOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MyGateApp API")
                        .version("v1")
                        .description("""
                                Phase 1 API for the MyGateApp resident/guard Flutter app and the society admin dashboard.

                                * Authenticate with `POST /api/v1/auth/request-otp` + `POST /api/v1/auth/verify-otp`, then send
                                  `Authorization: Bearer <accessToken>`.
                                * Success bodies are plain JSON objects; lists are Spring-style pages
                                  (`content`, `number`, `size`, `totalElements`, `totalPages`, `last`).
                                * Errors: `{ success:false, code, message, errors:[{field,message}], timestamp, path }`.
                                * Users with memberships in several societies may send `X-Society-Id` to pick the context.
                                """))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** Documents the optional society-context header on every operation. */
    @Bean
    public OperationCustomizer societyHeaderCustomizer() {
        return (operation, handlerMethod) -> operation.addParametersItem(new HeaderParameter()
                .name("X-Society-Id")
                .required(false)
                .description("Optional. Selects the society context for users who belong to more than one society.")
                .schema(new StringSchema().format("uuid")));
    }
}
