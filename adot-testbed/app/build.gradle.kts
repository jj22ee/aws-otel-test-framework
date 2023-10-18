/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java application project to get you started.
 * For more details on building Java & JVM projects, please refer to https://docs.gradle.org/8.2/userguide/building_java_projects.html in the Gradle documentation.
 */

plugins {
    // Apply the application plugin to add support for building a CLI application in Java.
    id ("java-library")
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Use JUnit Jupiter for testing.
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testImplementation("org.testcontainers:testcontainers:1.19.0")
    testImplementation("org.testcontainers:junit-jupiter:1.19.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.slf4j:slf4j-simple:1.7.36")
    testImplementation(platform("software.amazon.awssdk:bom:2.20.156"))
    testImplementation("software.amazon.awssdk:cloudwatchlogs")
    testImplementation("com.github.rholder:guava-retrying:2.0.0")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.13.0")

    // XRay
    api(platform("io.opentelemetry:opentelemetry-bom:1.30.1"))
    testImplementation("io.opentelemetry:opentelemetry-exporter-otlp")
	testImplementation("io.opentelemetry.contrib:opentelemetry-aws-xray:1.30.0")

    testImplementation("io.opentelemetry:opentelemetry-sdk:1.30.0");
    testImplementation("io.opentelemetry:opentelemetry-sdk-metrics:1.30.0");
    testImplementation("io.opentelemetry:opentelemetry-exporter-logging:1.30.0");
    testImplementation("io.opentelemetry:opentelemetry-semconv:1.30.0-alpha");

    testImplementation("software.amazon.awssdk:xray")
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
