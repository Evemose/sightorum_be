plugins {
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api("org.jooq:jooq")
    api("org.springframework.boot:spring-boot-autoconfigure")

    compileOnly("org.projectlombok:lombok")
    compileOnly("org.jspecify:jspecify")
    annotationProcessor("org.projectlombok:lombok")

    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testFixturesImplementation("org.jooq:jooq")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter")
    testFixturesImplementation("org.testcontainers:junit-jupiter")
    testFixturesImplementation("org.testcontainers:postgresql")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
