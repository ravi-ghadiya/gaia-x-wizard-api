/*
 * Copyright (c) 2023 | smartSense
 */

package eu.gaiax.wizard.config.swagger;

import eu.gaiax.wizard.config.security.model.SecurityConfigProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * The type Platform swagger config.
 */
@Configuration
@RequiredArgsConstructor
public class PlatformSwaggerConfig {
    private final SecurityConfigProperties properties;

    /**
     * Spring identity open api.
     *
     * @return the open api
     */
    @Bean
    public OpenAPI springIdentityOpenAPI() {
        Info info = this.apiInfo();
        OpenAPI openAPI = new OpenAPI();
        if (Boolean.TRUE.equals(this.properties.enabled())) {
            openAPI = this.enableSecurity(openAPI);
        }
        return openAPI.info(info);
    }

    private Info apiInfo() {
        return new Info()
                .title("The Smart-X API Documentation")
                .description("This API documentation contains all the APIs for The Smart-X")
                .version("1.0.0")
                .contact(new Contact()
                        .name("The Smart-X")
                        .email("admin@smartsensesolutions.com")
                        .url("https://gaiaxapi.proofsense.io/")
                );
    }

    private OpenAPI enableSecurity(OpenAPI openAPI) {
        Components components = new Components();
        components.addSecuritySchemes(
                "gaia-x-open-api",
                new SecurityScheme()
                        .type(SecurityScheme.Type.OAUTH2)
                        .flows(new OAuthFlows()
                                .authorizationCode(new OAuthFlow()
                                        .authorizationUrl(this.properties.authUrl())
                                        .tokenUrl(this.properties.tokenUrl())
                                        .refreshUrl(this.properties.refreshTokenUrl()
                                        )
                                )
                        )
        );
        return openAPI.components(components)
                .addSecurityItem(new SecurityRequirement().addList("gaia-x-open-api", Collections.emptyList()));
    }
}
