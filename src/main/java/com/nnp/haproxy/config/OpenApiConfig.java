package com.nnp.haproxy.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;

/**
 * OpenAPI 3.0 / Swagger UI Configuration for PICC-PC-Haproxy-Integration.
 */
@Configuration
public class OpenApiConfig {

	@Value("${server.port:8081}")
	private String serverPort;

	@Bean
	public OpenAPI customOpenAPI() {
		return new OpenAPI()
				.info(new Info()
						.title("PICC-PC-Haproxy-Integration REST API")
						.version("0.0.1-SNAPSHOT")
						.description("""
								REST API for orchestrating dynamic service registration, deregistration, \
								configuration reconciliation, and reloads in HAProxy via the DataPlane API.
								
								### Key Features:
								* **Asynchronous Registration / Deregistration**: Operations run in the background with automatic retries.
								* **Multi-Pattern Backend Support**: `K8S_DNS`, `EXTERNAL_IP`, `SSL`, `PATH_REWRITE`, `WEBSOCKET`, `TIME_CONFIGURABLE`, `TCP`.
								* **Transactional Safety**: All changes (backends, servers, ACLs, switching rules) are executed within atomic DataPlane API transactions.
								* **Two-Way Configuration Sync**: Reconciles on-disk `haproxy.cfg` file edits with the DataPlane API model.
								* **Reload Synchronization**: Polling until reload reaches a terminal state.
								""")
						.contact(new Contact()
								.name("Nubo Native Platform Team")
								.email("contribution@nubons.com")
								.url("https://github.com/Nubo-Native-Platform/PICC-PC-Haproxy-Integration"))
						.license(new License()
								.name("Apache License 2.0")
								.url("https://www.apache.org/licenses/LICENSE-2.0")))
				.servers(List.of(
						new Server().url("/").description("Default Server / Current Host"),
						new Server().url("http://localhost:" + serverPort).description("Local Development Server")
				))
				.tags(List.of(
						new Tag().name("HAProxy Integration").description("Endpoints for registering, deregistering, syncing, and inspecting HAProxy configurations"),
						new Tag().name("Diagnostics & Health").description("Service health and diagnostic status endpoints")
				));
	}
}
