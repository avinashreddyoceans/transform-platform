apply(plugin = "org.springframework.boot")
apply(plugin = "org.jetbrains.kotlin.plugin.spring")

tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = true }

dependencies {
    implementation(project(":platform-common"))
    implementation(project(":platform-core"))

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.kafka:spring-kafka")

    // Quartz
    implementation("org.springframework.boot:spring-boot-starter-quartz")

    runtimeOnly("org.postgresql:postgresql")
}
