rootProject.name = "kotlin-plugin-sample-sample"

pluginManagement {
    includeBuild("..")
}

includeBuild("..") {
    dependencySubstitution {
        substitute(module("ru.spbstu:kotlin-delegation-playground-library"))
            .using(project(":kotlin-delegation-playground-library"))
    }
}
