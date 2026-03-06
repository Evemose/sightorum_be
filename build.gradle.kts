plugins {
    java
    id("org.flywaydb.flyway") version "11.13.2"
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

buildscript {
    dependencies {
        classpath("org.postgresql:postgresql:42.7.4")
        classpath("org.flywaydb:flyway-database-postgresql:12.0.0")
    }
}

extra["springModulithVersion"] = "1.4.6"
extra["springAiVersion"] = "1.0.0"
val jspecifyVersion = "1.0.0"

subprojects {
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.springframework.boot")

    if (!this.plugins.hasPlugin("java-library")) {
        apply(plugin = "java")
    }

    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    extra["spring-ai.version"] = "1.1.2"

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.9")
            mavenBom("org.springframework.modulith:spring-modulith-bom:1.4.6")
            mavenBom("org.springframework.ai:spring-ai-bom:${property("spring-ai.version")}")
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

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("--enable-preview")
    }
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
