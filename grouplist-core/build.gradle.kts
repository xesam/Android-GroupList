plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.34.0"
    id("signing")
}

mavenPublishing {
    coordinates("io.github.xesam", "android-grouplist-core", "0.0.1")

    pom {
        name.set("android-grouplist-core")
        description.set("Android grouped list library: core state machine and shared protocols (pure JVM).")
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

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    testImplementation(libs.junit)
}
