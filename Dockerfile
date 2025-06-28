# 1) Build 단계: 공식 Maven 이미지로 의존성 캐시 및 패키징
FROM maven:3.9.4-eclipse-temurin-17 AS build

# 작업 디렉터리 설정
WORKDIR /app

# 1-1) pom.xml만 복사하여 의존성 미리 다운로드 (캐시 활용)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 1-2) 소스 코드 전체 복사 및 패키징
COPY src src
RUN mvn clean package -DskipTests

# 2) Run 단계: 경량화된 JRE 이미지를 사용하여 최종 JAR 실행
FROM eclipse-temurin:17-jre

# 애플리케이션 작업 디렉터리
WORKDIR /app

# 빌드 단계에서 생성된 JAR 복사
COPY --from=build /app/target/*.jar app.jar

# 애플리케이션 포트 노출 (Spring Boot 기본 8080 → Render 배포 시 포트 매핑)
EXPOSE 8080

# 컨테이너 시작 시 실행할 커맨드
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
