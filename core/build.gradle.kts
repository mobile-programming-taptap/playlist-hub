plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
tasks.register<JavaExec>("checkRecommendations") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.mptaptap.core.RecommendationCheck"
    jvmArgs("-ea")
}
tasks.check { dependsOn("checkRecommendations") }
tasks.test { failOnNoDiscoveredTests = false }
