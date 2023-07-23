plugins {
    java
    `java-library`
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "io.github.gaming32"
version = "1.0-SNAPSHOT"

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
    implementation("com.nitorcreations:javaxdelta:1.5")
    implementation("net.fabricmc:tiny-remapper:0.8.7")
    api("org.apache.commons:commons-compress:1.23.0")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}
