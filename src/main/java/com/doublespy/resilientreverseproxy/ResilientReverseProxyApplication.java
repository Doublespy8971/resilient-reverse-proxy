package com.doublespy.resilientreverseproxy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ResilientReverseProxyApplication {

	public static void main(String[] args) {
		SpringApplication.run(ResilientReverseProxyApplication.class, args);
	}

}
