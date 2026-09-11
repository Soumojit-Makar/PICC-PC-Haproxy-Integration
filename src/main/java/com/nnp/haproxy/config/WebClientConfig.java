package com.nnp.haproxy.config;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

@Configuration
@Slf4j
public class WebClientConfig {
	
	@Bean(name = "webClientK8SApi")
	public WebClient webClientKubernetesApi() {
		HttpClient httpClient = configWebClient();
		try {
			SslContext sslContext = SslContextBuilder
		            .forClient()
		            .trustManager(InsecureTrustManagerFactory.INSTANCE)
		            .build();
			httpClient = httpClient.secure(t -> t.sslContext(sslContext));
		} catch (SSLException e) {
            log.error("Error in httpclient creation disable ssl verification - {}", e.getMessage(), e);
		}

		return WebClient.builder().filter(logReq())
				.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.clientConnector(new ReactorClientHttpConnector(httpClient)).build();
	}
	
	private HttpClient configWebClient() {
        return HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 20000)
				.responseTimeout(Duration.ofMillis(20000))
				.doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(20000, TimeUnit.MILLISECONDS))
						.addHandlerLast(new WriteTimeoutHandler(20000, TimeUnit.MILLISECONDS)));
	}
	
	private ExchangeFilterFunction logReq() {
        // log.info("Logging request URL {}", req.url());
        return ExchangeFilterFunction.ofRequestProcessor(Mono::just);
	}

}
