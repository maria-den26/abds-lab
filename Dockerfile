ARG MAVEN_IMAGE
ARG JAVA_IMAGE
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp package
FROM ${JAVA_IMAGE} AS runtime
WORKDIR /app
COPY --from=build /build/target/energosbyt.jar /app/energosbyt.jar
ENTRYPOINT ["java", "-jar", "/app/energosbyt.jar"]
