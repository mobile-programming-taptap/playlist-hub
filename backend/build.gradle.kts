plugins {
    java
    id("org.springframework.boot") version "4.0.5"
}
repositories { mavenCentral() }
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.5"))
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.h2database:h2:2.5.252")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
sourceSets.main { resources.srcDir("../catalog") }
tasks.test {
    useJUnitPlatform(); dependsOn(":core:checkRecommendations")
    outputs.upToDateWhen { System.getenv("TAPTAP_LIVE_AI_CHECK") != "true" && System.getenv("TAPTAP_LIVE_YOUTUBE_KEY_CHECK") != "true" }
}
