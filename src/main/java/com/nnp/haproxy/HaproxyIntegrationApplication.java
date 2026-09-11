package com.nnp.haproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients
@EnableAsync
@EnableScheduling
public class HaproxyIntegrationApplication {

	public static void main(String[] args) {
		SpringApplication.run(HaproxyIntegrationApplication.class, args);
	}

}
