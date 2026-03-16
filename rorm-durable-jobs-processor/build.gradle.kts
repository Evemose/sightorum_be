plugins {
    `java-library`
}

dependencies {
    api(project(":rorm-durable-jobs"))
    implementation("com.palantir.javapoet:javapoet:0.6.0")
}
