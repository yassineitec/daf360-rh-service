package com.daf360.rh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RhServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(RhServiceApplication.class, args);
	}
}
