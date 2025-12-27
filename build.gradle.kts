plugins {
	java
	application
	idea
	id("com.palantir.git-version") version "4.2.0"
	id("io.freefair.lombok") version "9.1.0"
	id("com.google.cloud.tools.jib") version "3.5.2"
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
	
	implementation("com.google.guava:guava:33.5.0-jre")
	implementation("org.apache.commons:commons-text:1.15.0")
	
	val log4j2 = "2.17.2"
	implementation("org.apache.logging.log4j:log4j-api:$log4j2")
	implementation("org.apache.logging.log4j:log4j-core:$log4j2")
	implementation("org.apache.logging.log4j:log4j-slf4j-impl:$log4j2")
	
	// for interacting with docker daemon
	val docker = "3.7.0"
	implementation("com.github.docker-java:docker-java:$docker")
	implementation("com.github.docker-java:docker-java-transport-httpclient5:$docker")
	
	// for parsing cron schedule
	implementation("com.cronutils:cron-utils:9.2.1")
	
	// for paring command line arguments
	implementation("org.codehaus.plexus:plexus-utils:4.0.2")
	
	// for creating tar archive for uploading files to docker daemon
	implementation("org.apache.commons:commons-compress:1.28.0")
	implementation("com.google.code.gson:gson:2.13.2")
}

// set encoding for all compilation passes
tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
