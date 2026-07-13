import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

base {
    archivesName.set("glo-api")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        explicitApi()
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "glo-api"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "sdkBuild"
            url = uri(rootProject.layout.buildDirectory.dir("repository"))
        }
    }
}
