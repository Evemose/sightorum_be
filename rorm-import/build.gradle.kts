plugins {
    `java-library`
}

dependencies {
    api(project(":rorm-core"))
    api("org.springframework.boot:spring-boot-starter-batch")
    api("org.springframework.boot:spring-boot-starter-jdbc")

    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")

    compileOnly("org.projectlombok:lombok")
    compileOnly("org.jspecify:jspecify")
    annotationProcessor("org.projectlombok:lombok")

    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation(testFixtures(project(":rorm-core")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
