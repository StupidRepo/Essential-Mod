/*
 * Copyright (c) 2024 ModCore Inc. All rights reserved.
 *
 * This code is part of ModCore Inc.'s Essential Mod repository and is protected
 * under copyright registration # TX0009138511. For the full license, see:
 * https://github.com/EssentialGG/Essential/blob/main/LICENSE
 *
 * You may not use, copy, reproduce, modify, sell, license, distribute,
 * commercialize, or otherwise exploit, or create derivative works based
 * upon, this file or any other in this repository, all of which is reserved by Essential.
 */
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven(url = "https://maven.fabricmc.net/")
    maven(url = "https://maven.minecraftforge.net")
    maven(url = "https://maven.architectury.dev/")
    maven(url = "https://repo.essential.gg/repository/maven-public")
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())

    val kotlinCompilerVersion = "1.9.24"

    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinCompilerVersion")
    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinCompilerVersion")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:1.8.10")
    implementation("org.jetbrains.kotlinx:binary-compatibility-validator:0.18.1")
    runtimeOnly("org.jetbrains.kotlin:kotlin-metadata-jvm:2.2.0") // for binary-compatibility-validator
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:8.3.7")
    implementation("org.ow2.asm:asm-commons:9.3")
    implementation ("com.google.guava:guava:30.1.1-jre")

    implementation("gg.essential:essential-gradle-toolkit:0.6.10")
    implementation("gg.essential.loom:gg.essential.loom.gradle.plugin:1.7.35") // TODO remove once EGT has updated
}

gradlePlugin {
    plugins {
        create("mixin") {
            id = "gg.essential.mixin"
            implementationClass = "gg.essential.gradle.MixinPlugin"
        }
        create("bundle") {
            id = "gg.essential.bundle"
            implementationClass = "gg.essential.gradle.BundlePlugin"
        }
        create("relocate") {
            id = "gg.essential.relocate"
            implementationClass = "gg.essential.gradle.RelocatePlugin"
        }
    }
}
