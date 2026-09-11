package com.nnp.haproxy.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
public class HaproxyFeignConfig {
	@Value("${haproxy.dataplane.user:dataplaneapi}")
	private String username;
	@Value("${haproxy.dataplane.password:}")
    private String password;

    @Bean
    public RequestInterceptor basicAuthRequestInterceptor() {
        return template -> {
            String auth = username + ":" + password;
            byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
            String authHeader = "Basic " + new String(encodedAuth, StandardCharsets.UTF_8);
            template.header("Authorization", authHeader);
        };
    }

}
