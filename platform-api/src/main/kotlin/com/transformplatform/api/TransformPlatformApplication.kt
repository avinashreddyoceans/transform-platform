package com.transformplatform.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@EnableAsync
// Scan all sub-packages across every platform-* module
@ComponentScan(basePackages = ["com.transformplatform"])
// Register JPA entities from platform-integration (different package from the boot module)
@EntityScan(basePackages = ["com.transformplatform"])
// Register Spring Data repositories from all modules
@EnableJpaRepositories(basePackages = ["com.transformplatform"])
class TransformPlatformApplication

fun main(args: Array<String>) {
    runApplication<TransformPlatformApplication>(*args)
}
