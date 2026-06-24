package com.example.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WalletPaymentApplication {

	public static void main(String[] args) {
		SpringApplication.run(WalletPaymentApplication.class, args);
	}
}
