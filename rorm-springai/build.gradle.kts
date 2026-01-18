plugins {
    `java-library`
}

tasks.test {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
    maven { url = uri("https://repo.spring.io/snapshot") }
}

val springAiVersion = "1.0.0"

dependencies {
    api(project(":rorm-core"))
    api(project(":rorm-serialization"))
    api(project(":rorm-import"))

    api(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
    api("org.springframework.ai:spring-ai-starter-model-openai")

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")

    compileOnly("org.jspecify:jspecify")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}
