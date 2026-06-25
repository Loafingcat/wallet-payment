# 멀티스테이지: 루트의 Gradle 래퍼로 fake-pg 모듈만 빌드한 다음, 가벼운 JRE 이미지로 실행한다.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew && ./gradlew :fake-pg:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/fake-pg/build/libs/*.jar app.jar
EXPOSE 9999
ENTRYPOINT ["java", "-jar", "app.jar"]
