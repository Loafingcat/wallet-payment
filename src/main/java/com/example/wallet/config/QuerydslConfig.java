package com.example.wallet.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.querydsl.jpa.impl.JPAQueryFactory;

// QueryDSL을 쓰려면 JPAQueryFactory가 필요하고, 그건 EntityManager로 만든다.
// Spring Data JPA 리포지토리(findById 등)만으로는 "기간 + 가맹점" 같은 조건이 있을 때만
// WHERE를 붙이는 동적 쿼리를 짤 수 없다 — 메서드 이름으로 조합하거나 @Query를 여러 개
// 만들어야 한다. QueryDSL은 BooleanBuilder로 조건을 코드로 조립할 수 있어서, 정산처럼
// "필터가 있을 수도, 없을 수도 있는" 집계 쿼리에 적합하다.
@Configuration
public class QuerydslConfig {

	@PersistenceContext
	private EntityManager entityManager;

	@Bean
	public JPAQueryFactory jpaQueryFactory() {
		return new JPAQueryFactory(entityManager);
	}
}
