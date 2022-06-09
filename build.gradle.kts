plugins {
	java
	application
	idea
	id("com.palantir.git-version") version "0.12.3"
	id("io.freefair.lombok") version "6.3.0"
	id("com.google.cloud.tools.jib") version "3.1.4"
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
		languageVersion.set(JavaLanguageVersion.of(17))
	}
}

application {
	mainClass.set("de.chrisliebaer.salvage.SalvageMain")
}

jib {
	val javaVersion = java.toolchain.languageVersion.get().asInt()
	from.image = "eclipse-temurin:$javaVersion"
	
	container {
		//jvmFlags = mutableListOf("-agentlib:jdwp=transport=dt_socket,server=n,address=192.168.200.1:5005,suspend=y")
	}
	
	to {
		
		// here be dragons
		System.getenv("IMAGE_TAGS")?.apply {
			tags = split("[,\\n]".toRegex())
					.map { it.split(":")[1] }
					.toCollection(mutableSetOf())
		}
	}
}

repositories {
	mavenLocal()
	mavenCentral()
}


dependencies {
	
	implementation("com.google.guava:guava:31.1-jre")
	
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
	
	// for creating tar archive for uploading files to docker daemon
	implementation("org.apache.commons:commons-compress:1.21")
	implementation("com.google.code.gson:gson:2.9.0")
}

// set encoding for all compilation passes
tasks.withType<JavaCompile> {
	options.encoding = "UTF-8"
}
