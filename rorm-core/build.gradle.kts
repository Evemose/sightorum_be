plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("org.jooq:jooq")
    api("org.springframework.boot:spring-boot-autoconfigure")
    implementation("jakarta.annotation:jakarta.annotation-api")

    compileOnly("org.projectlombok:lombok")
    compileOnly("org.jspecify:jspecify")
    annotationProcessor("org.projectlombok:lombok")

    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testFixturesApi("org.jooq:jooq")
    testFixturesApi("org.junit.jupiter:junit-jupiter")
    testFixturesApi("org.testcontainers:junit-jupiter")
    testFixturesApi("org.testcontainers:postgresql")
    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
