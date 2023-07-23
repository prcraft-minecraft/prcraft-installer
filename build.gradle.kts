plugins {
    java
    `java-library`
    application
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.github.gaming32"
version = "1.0.0"

application {
    mainClass.set("io.github.gaming32.prcraftinstaller.PrcraftInstaller")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net") {
        name = "Fabric"
    }
}

dependencies {
    api("com.nitorcreations:javaxdelta:1.5")
    api("net.fabricmc:tiny-remapper:0.8.7")
    api("org.apache.commons:commons-compress:1.23.0")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

publishing {
    repositories {
        maven {
            name = "gaming32"
            credentials(PasswordCredentials::class)

            val baseUri = "https://maven.jemnetworks.com"
            url = uri(baseUri + if (version.toString().endsWith("-SNAPSHOT")) "/snapshots" else "/releases")
        }
    }
}
