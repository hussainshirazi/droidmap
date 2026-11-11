gradlePlugin {
    plugins {
        register("feature") {
            id = libs.plugins.sample.feature.get().pluginId
            implementationClass = "FeatureConventionPlugin"
        }
    }
}
