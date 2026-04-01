plugins {
    alias(libs.plugins.android.library)
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

android {
    namespace = "io.github.xesam.android.grouplist.recyclerview"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 21

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

mavenPublishing {
    coordinates("io.github.xesam", "android-grouplist-recyclerview", "0.0.1")

    pom {
        name.set("android-grouplist-recyclerview")
        description.set("Android grouped list library: RecyclerView engine adapter layer.")
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
            developerConnection.set("scm:git:ssh://git@github.com/xesam/Android-GroupList.git")
            url.set("https://github.com/xesam/Android-GroupList")
        }
    }

    publishToMavenCentral()
    signAllPublications()
}

dependencies {
    api(project(":grouplist-core"))
    api(libs.recyclerview)
    testImplementation(libs.junit)
    // 无 androidTest 源码：androidTest 依赖是模板残留，已移除
}
