plugins {
    `java-library`
}

dependencies {
    api(project(":rorm-core"))
    api("org.springframework.boot:spring-boot-starter-json")

    compileOnly("org.jspecify:jspecify")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
