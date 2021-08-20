plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {}
    js(BOTH) {
        browser {}
        nodejs {}
    }
    linuxX64 {}

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
    }
}
