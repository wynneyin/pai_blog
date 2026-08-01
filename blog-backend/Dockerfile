FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/target/blog-backend-1.0.0.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS="-Xms128m -Xmx512m -XX:MaxMetaspaceSize=128m -Dfile.encoding=UTF-8"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
