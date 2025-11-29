import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	kotlin("jvm") version "1.9.21"
	kotlin("plugin.spring") version "1.9.21"
	id("org.springframework.boot") version "3.2.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.alfredoalpizar"
version = "0.0.1-SNAPSHOT"

java {
	sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot Core
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// Database (only needed when using storage-mode: database)
	// For in-memory mode (default), no database setup required
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.flywaydb:flyway-core")
	implementation("org.flywaydb:flyway-database-postgresql:10.10.0")
	// PostgreSQL driver - uncomment when using database mode
	// runtimeOnly("org.postgresql:postgresql")

	// Kotlin Support
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.7.3")

	// JSON Processing
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
	implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

	// AWS SDK v2 for DynamoDB (distributed locks)
	implementation(platform("software.amazon.awssdk:bom:2.21.0"))
	implementation("software.amazon.awssdk:dynamodb")
	implementation("software.amazon.awssdk:netty-nio-client") // Async HTTP client

	// Logging
	implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

	// Configuration
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
	testImplementation("io.mockk:mockk:1.13.8")
	testImplementation("com.ninja-squad:springmockk:4.0.2")
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		freeCompilerArgs += "-Xjsr305=strict"
		jvmTarget = "21"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Frontend build integration
tasks.register<Exec>("buildFrontend") {
	workingDir = file("frontend")
	commandLine("npm", "run", "build:prod")

	// Ensure npm install has been run
	doFirst {
		if (!file("frontend/node_modules").exists()) {
			exec {
				workingDir = file("frontend")
				commandLine("npm", "install")
			}
		}
	}
}

// Make bootJar depend on frontend build for production
tasks.named("bootJar") {
	dependsOn("buildFrontend")
}
