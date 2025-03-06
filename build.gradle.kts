plugins {
	java
	application
	idea
	id("com.palantir.git-version") version "0.12.3"
	id("io.freefair.lombok") version "8.12.2.1"
	id("com.google.cloud.tools.jib") version "3.4.4"
}

idea {
	module {
		isDownloadSources = true
		isDownloadJavadoc = true
	}
}

group = "de.chrisliebaer.salvage"
val gitVersion: groovy.lang.Closure<String> by extra
version = gitVersion()

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(21))
	}
}

application {
	mainClass.set("de.chrisliebaer.salvage.SalvageMain")
}

jib {
	val javaVersion = java.toolchain.languageVersion.get().asInt()
	from {
		image = "eclipse-temurin:$javaVersion"
		platforms {
			platform {
				architecture = "amd64"
				os = "linux"
			}
			platform {
				architecture = "arm64"
				os = "linux"
			}
		}
	}
}


repositories {
	mavenLocal()
	mavenCentral()
}


dependencies {
	
	implementation("com.google.guava:guava:31.1-jre")
	implementation("org.apache.commons:commons-text:1.10.0")
	
	val log4j2 = "2.17.2"
	implementation("org.apache.logging.log4j:log4j-api:$log4j2")
	implementation("org.apache.logging.log4j:log4j-core:$log4j2")
	implementation("org.apache.logging.log4j:log4j-slf4j-impl:$log4j2")
	
	// for interacting with docker daemon
	val docker = "3.2.13"
	implementation("com.github.docker-java:docker-java:$docker")
	implementation("com.github.docker-java:docker-java-transport-httpclient5:$docker")
	
	// for parsing cron schedule
	implementation("com.cronutils:cron-utils:9.1.6")
	
	// for paring command line arguments
	implementation("org.codehaus.plexus:plexus-utils:3.4.2")
	
	// for creating tar archive for uploading files to docker daemon
	implementation("org.apache.commons:commons-compress:1.21")
	implementation("com.google.code.gson:gson:2.9.0")
}

// set encoding for all compilation passes
tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
