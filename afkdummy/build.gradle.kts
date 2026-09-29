plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.8"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group   = "com.lo.afkdummy"
version = "1.0.0"
description = "AFK Dummy — persistent fake-player entities for Paper"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // NMS + Paper API via paperweight userdev (Mojang-mapped source access)
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    // shadowJar runs reobfJar first so the output is production-ready
    shadowJar {
        archiveFileName.set("afkdummy.jar")
        // No runtime deps to shade — pull content from the reobfed jar
        configurations = listOf()
        from(zipTree(reobfJar.get().outputJar.asFile.get()))
        dependsOn(reobfJar)
    }

    assemble {
        dependsOn(shadowJar)
    }
}
