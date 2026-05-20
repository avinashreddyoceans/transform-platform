package com.transformplatform.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync

// scanBasePackages uses @SpringBootApplication's own @ComponentScan, which
// includes Spring Boot's TypeExcludeFilter. A separate @ComponentScan would
// create a second scan without that filter, breaking @WebMvcTest slicing.
// JPA entity scan + repository registration live in JpaConfig so that
// @WebMvcTest slices can boot without an EntityManagerFactory.
@SpringBootApplication(scanBasePackages = ["com.transformplatform"])
@EnableAsync
class TransformPlatformApplication

fun main(args: Array<String>) {
    runApplication<TransformPlatformApplication>(*args)
}
