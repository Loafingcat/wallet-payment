package com.example.fakepg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

// fake-pg는 일부러 DB/메시징이 전혀 없는 단순한 앱이다. 테스트에서는 wallet-payment의
// 전체 클래스패스(JPA, AMQP 등)와 같은 JVM 안에서 같이 뜨는데, Spring Boot는 "그 클래스가
// 클래스패스에 있다"는 이유만으로 DataSource/JPA/AMQP 자동 설정을 시도한다 — 이 앱엔
// spring.datasource.url 같은 설정이 전혀 없으니 그 자동 설정은 항상 실패한다. 그래서
// 명시적으로 빼둔다(단독 실행 시에는 원래 이 클래스들이 클래스패스에 없어서 어차피
// 트리거되지 않았을 자동 설정이라, 빼도 단독 실행 동작은 그대로다).
@SpringBootApplication(exclude = {
		DataSourceAutoConfiguration.class,
		HibernateJpaAutoConfiguration.class,
		RabbitAutoConfiguration.class
})
public class FakePgApplication {

	public static void main(String[] args) {
		SpringApplication.run(FakePgApplication.class, args);
	}
}
