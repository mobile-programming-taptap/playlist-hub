pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "taptap-backend"
include(":core")
project(":core").projectDir = file("../core")
