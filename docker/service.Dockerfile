# 서비스 6개가 함께 쓰는 이미지. 빌드 컨텍스트는 각 모듈의 build/libs이고, 그 안의 bootJar 하나를 복사한다.
# 먼저 `./gradlew bootJar`로 jar를 만든다.
FROM eclipse-temurin:25-jre

# compose.yaml의 헬스 체크가 curl을 쓴다. 기본 이미지에는 curl이 없다.
# jar를 복사하기 전에 설치해 서비스 6개의 이미지가 이 층을 함께 쓴다
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --no-create-home weave

WORKDIR /app
COPY *.jar app.jar

USER weave
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
