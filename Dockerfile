FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM amazoncorretto:17-alpine3.22
WORKDIR /app
COPY --from=build /build/target/dicom-receiver.jar /app/dicom-receiver.jar
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/dicom-receiver.jar"]
