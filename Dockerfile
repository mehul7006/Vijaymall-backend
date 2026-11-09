# Build: mvn -q package -DskipTests, then docker build -t vijay-mall-api .
FROM eclipse-temurin:27-jre
WORKDIR /app
COPY target/mall-api-0.1.0-SNAPSHOT.jar app.jar
RUN mkdir -p /app/data/images /app/data/files
ENV SPRING_PROFILES_ACTIVE=prod IMAGES_DIR=/app/data/images FILES_DIR=/app/data/files
EXPOSE 8080 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
