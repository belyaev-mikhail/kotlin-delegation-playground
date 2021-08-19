plugins {
  kotlin("jvm") version "1.5.10" apply false
  kotlin("multiplatform") version "1.5.10" apply false
  id("org.jetbrains.dokka") version "0.10.0" apply false
  id("com.gradle.plugin-publish") version "0.11.0" apply false
  id("com.github.gmazzo.buildconfig") version "2.0.2" apply false
}

allprojects {
  group = "ru.spbstu"
  version = "0.0.2"
}

subprojects {
  repositories {
    mavenCentral()
    jcenter()
  }
}
