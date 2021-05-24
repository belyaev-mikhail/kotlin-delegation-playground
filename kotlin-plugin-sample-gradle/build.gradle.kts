import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("java-gradle-plugin")
  kotlin("jvm")
  id("com.gradle.plugin-publish")
  id("com.github.gmazzo.buildconfig")
}

dependencies {
  implementation(kotlin("stdlib"))
  implementation(kotlin("gradle-plugin-api"))
}

buildConfig {
  val project = project(":kotlin-plugin-sample-plugin")
  packageName(project.group.toString())
  buildConfigField("String", "PLUGIN_GROUP_ID", "\"${project.group}\"")
  buildConfigField("String", "PLUGIN_ARTIFACT_ID", "\"${project.name}\"")
  buildConfigField("String", "PLUGIN_VERSION", "\"${project.version}\"")
}

pluginBundle {
  website = "https://github.com/vorpal-research/kotlin-plugin-sample"
  vcsUrl = "https://github.com/vorpal-research/kotlin-plugin-sample.git"
  tags = listOf("kotlin", "plugin-sample")
}

gradlePlugin {
  plugins {
    create("kotlinSamplePlugin") {
      id = "ru.spbstu.kotlin-plugin-sample"
      displayName = "Kotlin Sample Plugin"
      description = "Kotlin Compiler Plugin example"
      implementationClass = "ru.spbstu.KotlinSampleGradlePlugin"
    }
  }
}

tasks.withType<KotlinCompile> {
  kotlinOptions.jvmTarget = "1.8"
}

tasks.register("publish") {
  dependsOn("publishPlugins")
}
