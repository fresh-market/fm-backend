# 이미지 태그는 커밋 SHA 다. latest 는 어디에도 쓰지 않는다.
# jar 는 CI 가 ./gradlew bootJar 로 미리 만든다. 여기서는 담기만 한다.
FROM eclipse-temurin:21.0.9_10-jre-noble

# compose 의 healthcheck 가 curl 로 liveness 를 찌른다. jre 이미지에는 없어 넣는다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# root 로 돌리지 않는다.
RUN useradd --system --create-home --uid 10001 app
USER app
WORKDIR /home/app

COPY --chown=app:app build/libs/*.jar app.jar

# 8080 은 트래픽, 8081 은 액추에이터다.
EXPOSE 8080 8081

# JAVA_TOOL_OPTIONS 로 힙 비율과 DNS TTL 을 준다. compose 가 넣는다.
ENTRYPOINT ["java", "-jar", "/home/app/app.jar"]
