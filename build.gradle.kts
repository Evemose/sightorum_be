plugins {
    java
    id("org.springframework.boot") version "3.5.9"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.rorm"
version = "0.0.1-SNAPSHOT"
description = "rorm"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

extra["springModulithVersion"] = "1.4.6"
val jspecifyVersion = "1.0.0"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.9")
            mavenBom("org.springframework.modulith:spring-modulith-bom:1.4.6")
        }
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        "compileOnly"("org.jspecify:jspecify:$jspecifyVersion")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        maxParallelForks = Runtime.getRuntime().availableProcessors()
        systemProperty("junit.jupiter.execution.parallel.enabled", "true")
        systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
        systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    }
}

dependencies {
    implementation(project(":rorm-core"))
    implementation(project(":rorm-jpasupport"))
    implementation(project(":rorm-serialization"))

    implementation("org.springframework.boot:spring-boot-starter")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxParallelForks = Runtime.getRuntime().availableProcessors()
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
}
