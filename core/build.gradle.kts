import com.vanniktech.maven.publish.SonatypeHost

extra["projectDescription"] =
    "Yet Another Functional Reactive Library - core library"

plugins {
    kotlin("multiplatform") version "2.1.10"
    id("org.jetbrains.kotlinx.atomicfu") version "0.27.0"
    id("org.jetbrains.dokka") version "2.0.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("com.vanniktech.maven.publish") version "0.31.0"
}

kotlin {
    jvm()
    iosArm64()
    macosX64()
    js().browser()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-annotations-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

//mavenPublishing {
//    publishToMavenCentral(SonatypeHost.S01)
//
//    signAllPublications()
//
//    coordinates(group.toString(), project.name, version.toString())
//
//    pom {
//        name = project.name
//        description = projectDescription
//        inceptionYear = "2025"
//        url = "https://github.com/sintrastes/yafrl/"
//        licenses {
//            license {
//                name = "The MIT License"
//                url = "https://opensource.org/license/mit"
//                distribution = "https://opensource.org/license/mit"
//            }
//        }
//        developers {
//            developer {
//                id = "sintrastes"
//                name = "Nathan Bedell"
//                url = "https://github.com/sintrastes/"
//            }
//        }
//        scm {
//            url = "https://github.com/sintrastes/yafrl/"
//            connection = "scm:git:git://github.com/sintrastes/yafrl.git"
//            developerConnection = "scm:git:ssh://git@github.com/sintrastes/yafrl.git"
//        }
//    }
//}
