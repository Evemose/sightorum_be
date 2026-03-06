import org.flywaydb.gradle.FlywayExtension
import java.util.*

TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

plugins {
    java
    id("org.flywaydb.flyway") version "11.13.2"
}

tasks.test {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Duser.timezone=UTC")
}

configure<FlywayExtension> {
    url = "jdbc:postgresql://localhost:5444/mydatabase"
    user = "myuser"
    password = "mypassword"
    schemas = arrayOf("public")
    locations = arrayOf("classpath:db/migration")
}

buildscript {
    dependencies {
        classpath("org.postgresql:postgresql:42.7.4")
        classpath("org.flywaydb:flyway-database-postgresql:12.0.0")
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
    maven { url = uri("https://repo.spring.io/snapshot") }
}

val hypersistenceVersion = "3.14.1"
val mapstructVersion = "1.6.3"

dependencies {
    implementation(project(":rorm-core"))
    implementation(project(":rorm-serialization"))
    implementation(project(":rorm-import"))
    implementation(project(":rorm-springai"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("io.hypersistence:hypersistence-utils-hibernate-63:$hypersistenceVersion")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    implementation("org.mapstruct:mapstruct:$mapstructVersion")

    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.jspecify:jspecify")
    compileOnly("org.projectlombok:lombok")
    compileOnly("org.mapstruct:mapstruct:$mapstructVersion")

    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}
