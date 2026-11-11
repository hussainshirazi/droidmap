pluginManagement { includeBuild("build-logic") }
rootProject.name = "sample"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":app")
include(":core:database", ":core:data")
include(":feature:home")
include(":feature:detail-screen")
