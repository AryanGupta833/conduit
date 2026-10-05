package com.aryan.conduit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ConduitApplication {

	public static void main(String[] args) {
		SpringApplication.run(ConduitApplication.class, args);
	}

}
