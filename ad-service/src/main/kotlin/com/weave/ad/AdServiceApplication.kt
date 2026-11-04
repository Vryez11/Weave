package com.weave.ad

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AdServiceApplication

fun main(args: Array<String>) {
    runApplication<AdServiceApplication>(*args)
}
