import com.vanniktech.maven.publish.SonatypeHost

extra["projectDescription"] =
    "Yet Another Functional Reactive Library - core library"

plugins {
    kotlin("multiplatform") version "2.1.10"
    id("org.jetbrains.kotlinx.atomicfu") version "0.27.0"
    id("org.jetbrains.dokka") version "2.0.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
    id("com.vanniktech.maven.publish") version "0.31.0"
    id("io.kotest.multiplatform") version "6.0.0.M2"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    jvm()
    iosArm64()
    macosX64()
    js(IR) {
        browser {
            testTask {
                // useMocha()

                // Currently failing `Tick emits events at specified intervals` for some reason.
                enabled = false
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-annotations-common"))
                implementation("io.kotest:kotest-assertions-core:6.0.0.M2")
                implementation("io.kotest:kotest-framework-engine:6.0.0.M2")
                implementation("io.kotest:kotest-property:6.0.0.M2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("io.kotest:kotest-runner-junit5:6.0.0.M2")
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
