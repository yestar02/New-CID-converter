# Use an OpenJDK 21 base image for building
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Copy Maven wrapper and dependencies
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B
# Copy source code and build the jar
COPY src src
RUN ./mvnw clean package -DskipTests

# Use a lightweight JRE image for running
FROM eclipse-temurin:21-jre
WORKDIR /app
# Copy the built jar from the build stage
COPY --from=build /app/target/*.jar app.jar
# Expose port 3000
EXPOSE 3000
# Set the default command to run the jar
ENTRYPOINT ["java", "-jar", "app.jar"]
