package com.example.wallet.support;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import com.example.fakepg.FakePgApplication;

// @Container/@Testcontainers를 쓰지 않고 직접 start()한다. 그 둘을 쓰면 JUnit이 "이 컨테이너를
// 선언한 테스트 클래스가 끝나면 멈춘다"고 가정하는데, 이 클래스는 여러 테스트 클래스가 상속해서
// static 필드를 공유하므로, 먼저 끝난 테스트 클래스가 다음 테스트 클래스가 쓸 컨테이너를 멈춰버린다.
// 수동 start()는 JVM이 끝날 때 Testcontainers의 Ryuk이 정리해주므로 안전하다.
//
// 클래스 레벨 @Transactional: 테스트 메서드 하나가 끝나면 그 안에서 일어난 모든 DB 변경이
// 자동으로 rollback된다. 그래서 같은 테스트 클래스의 다른 메서드가 이전 메서드가 남긴 데이터와
// 충돌하지 않는다(예: 같은 userId로 Wallet을 또 만들어도 unique 제약 위반이 안 남).
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public abstract class IntegrationTestSupport {

	@ServiceConnection
	static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

	@ServiceConnection
	static final RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

	// fake-pg(S9)는 Testcontainers로 띄우지 않는다 — 일반 도커 이미지가 아니라 우리가 만든
	// Spring Boot 앱이라, 같은 JVM 안에서 별도 ApplicationContext로 직접 띄우는 쪽이 빠르고
	// 간단하다. 그래도 통신은 실제 루프백 HTTP라서 타임아웃/연결 끊김 같은 증상은 똑같이 겪는다.
	// 개발자가 수동으로 띄워둔 fake-pg(기본 9999)와 안 부딫히게 19999를 쓴다.
	//
	// .properties(...)가 아니라 run("--server.port=19999")로 넘기는 이유: 이 둘은 같은
	// 테스트 classpath에 wallet-payment의 application.yml(server.port: 8080)도 같이
	// 올라가 있어서, 클래스로더가 어느 모듈의 application.yml을 먼저 찾느냐에 따라 fake-pg가
	// 의도와 다르게 8080을 물려받을 수 있다. 커맨드라인 인자는 application.yml보다 항상
	// 우선순위가 높아서, 어느 application.yml이 로드되든 상관없이 포트를 확정시킨다.
	static final ConfigurableApplicationContext fakePg = new SpringApplicationBuilder(FakePgApplication.class)
			.run("--server.port=19999");

	static {
		mysql.start();
		rabbitMq.start();
	}
}
