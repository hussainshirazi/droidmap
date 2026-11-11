plugins { alias(libs.plugins.android.application) }
android { namespace = "com.example.app" }
dependencies {
    implementation(projects.feature.home)
    implementation(projects.feature.detailScreen)
    implementation(project(":core:database"))
    implementation(project(":core:missing"))
    testImplementation(projects.core.data)
}
