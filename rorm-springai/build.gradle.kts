plugins {
    `java-library`
}

java {
    modularity.inferModulePath = true
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        listOf("--add-reads", "rorm.rorm.springai.main=ALL-UNNAMED")
    )
}

tasks.test {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
    jvmArgs("--enable-preview")
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    jvmArgs("--add-reads", "rorm.rorm.springai.main=ALL-UNNAMED")
    project.properties.filter { (k, _) -> k.startsWith("blackbox.") }
        .forEach { (k, v) -> systemProperty(k, v.toString()) }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
    maven { url = uri("https://repo.spring.io/snapshot") }
}

val hypersistenceVersion by extra("3.14.1")
val mapstructVersion by extra("1.6.3")
val restateVersion by extra("2.4.1")

dependencies {
    api(project(":rorm-core"))
    api(project(":rorm-serialization"))
    api(project(":rorm-import"))

    api("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-client-chat")

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("io.hypersistence:hypersistence-utils-hibernate-63:$hypersistenceVersion")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    api("org.springframework.ai:spring-ai-advisors-vector-store")
    implementation("com.anthropic:anthropic-java:2.15.0")
    implementation("com.anthropic:anthropic-java-bedrock:2.15.0")
    implementation("com.bucket4j:bucket4j-core:8.10.1")

    // Restate durable execution (conditional via rorm.ml.durable-execution=true)
    implementation("dev.restate:sdk-spring-boot-starter:$restateVersion")
    implementation("dev.restate:admin-client:$restateVersion")
    annotationProcessor("dev.restate:sdk-api-gen:$restateVersion")

    compileOnly("org.jspecify:jspecify")
    compileOnly("org.projectlombok:lombok")

    implementation("org.mapstruct:mapstruct:$mapstructVersion")
    annotationProcessor("org.mapstruct:mapstruct-processor:$mapstructVersion")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok-mapstruct-binding:0.2.0")

    testImplementation("dev.restate:sdk-testing:$restateVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.wiremock.integrations.testcontainers:wiremock-testcontainers-module:1.0-alpha-14")
    testImplementation("org.wiremock:wiremock-standalone:3.12.1")
    testImplementation(testFixtures(project(":rorm-core")))
    testRuntimeOnly("org.springframework.boot:spring-boot-docker-compose")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testAnnotationProcessor("dev.restate:sdk-api-gen:$restateVersion")
}
