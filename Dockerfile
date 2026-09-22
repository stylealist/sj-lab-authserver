FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# 빌드된 JAR 파일을 복사 (먼저 mvnw.cmd clean package 필요)
COPY target/sj-lab-authserver.jar /app/sj-lab-authserver.jar

# 활성 프로파일과 AUTH_JWT_SECRET 은 helm/ConfigMap·Secret 에서 주입
ENTRYPOINT ["java", "-jar", "/app/sj-lab-authserver.jar"]
