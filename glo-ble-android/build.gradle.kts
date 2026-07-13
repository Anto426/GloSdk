plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.anto426.glo.sdk.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":glo-api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = project.group.toString()
                artifactId = "glo-ble-android"
                version = project.version.toString()
                from(components["release"])
            }
        }
        repositories {
            maven {
                name = "sdkBuild"
                url = uri(rootProject.layout.buildDirectory.dir("repository"))
            }
        }
    }
}
