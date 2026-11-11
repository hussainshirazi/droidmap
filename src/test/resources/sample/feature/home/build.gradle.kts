plugins { alias(libs.plugins.sample.feature) }
dependencies {
    implementation(projects.feature.detailScreen)
}
