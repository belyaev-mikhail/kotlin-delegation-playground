plugins {
    kotlin("multiplatform") version "1.5.10"
    id("ru.spbstu.kotlin-delegation-playground") version "0.0.2"
}

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    js(IR) {
        browser {
            testTask {
                useKarma {
                    useFirefoxHeadless()
                }
            }
        }
        nodejs()
    }

    val osName = System.getProperty("os.name")
    when {
        "Windows" in osName -> mingwX64("native")
        "Mac OS" in osName -> macosX64("native")
        else -> linuxX64("native")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("ru.spbstu:kotlin-delegation-playground-library")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
        val nativeMain by getting {
            dependsOn(commonMain)
        }
        val nativeTest by getting {
            dependsOn(commonTest)
        }
    }
}

configure<ru.spbstu.KotlinDelegationPlaygroundGradleExtension> {
    annotationNames = listOf(
        "ru.spbstu.DataLike"
    )
}
