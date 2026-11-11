import org.gradle.api.Plugin
import org.gradle.api.Project

class FeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            dependencies {
                "implementation"(project(":core:data"))
            }
        }
    }
}
