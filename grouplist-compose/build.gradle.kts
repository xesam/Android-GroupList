plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

android {
    namespace = "io.github.xesam.android.grouplist.compose"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
}

mavenPublishing {
    coordinates("io.github.xesam", "android-grouplist-compose", "0.0.1")

    pom {
        name.set("android-grouplist-compose")
        description.set("Android grouped list library: Compose engine (LazyColumn + observable GroupListState).")
        url.set("https://github.com/xesam/Android-GroupList")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("xesam")
                name.set("xesam")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/xesam/Android-GroupList.git")
            developerConnection.set("scm:git:ssh://git@github.com:xesam/Android-GroupList.git")
            url.set("https://github.com/xesam/Android-GroupList")
        }
    }

    publishToMavenCentral()
    signAllPublications()
}

dependencies {
    api(project(":grouplist-core"))
    api(platform(libs.bom.compose))
    api(libs.compose.foundation)
    api(libs.compose.ui)
    testImplementation(libs.junit)
}
