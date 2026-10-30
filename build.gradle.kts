plugins {
    java
}

group = "it.farrlor"
version = "1.1.0"

repositories {
    mavenCentral()

    // Paper API
    maven("https://repo.papermc.io/repository/maven-public/")

    // PacketEvents
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {

    // Paper 26.3
    compileOnly("io.papermc.paper:paper-api:26.3.build.231-stable")

    // PacketEvents 2.14.0
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("com.github.retrooper:packetevents-spigot:2.14.0")
    testImplementation("io.papermc.paper:paper-api:26.3.build.231-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

tasks.test { useJUnitPlatform() }

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}
