package com.example.wallet.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

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

	static {
		mysql.start();
		rabbitMq.start();
	}
}
