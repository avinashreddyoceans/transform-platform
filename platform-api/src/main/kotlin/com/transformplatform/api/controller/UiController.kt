package com.transformplatform.api.controller

import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

// ── UiController ──────────────────────────────────────────────────────────────
//
// Serves the React SPA shell (index.html) for all /ui/** deep-link routes.
//
// Static assets (*.js, *.css, *.svg) are NOT matched here because the [^.]+
// regex on every path variable rejects any segment that contains a dot.
// Those requests fall through to Spring Boot's default classpath static-resource
// handler which serves files from classpath:static/.
//
// index.html is streamed directly from the classpath rather than using forward:
// because a forward internal dispatch would re-enter DispatcherServlet and risk
// re-matching this same controller or the exception handler.

@Controller
class UiController {

    private val indexHtml: ClassPathResource = ClassPathResource("static/ui/index.html")

    @GetMapping("/")
    fun root(): String = "redirect:/ui/"

    @GetMapping(
        "/ui",
        "/ui/",
        "/ui/{a:[^.]+}",
        "/ui/{a:[^.]+}/{b:[^.]+}",
        "/ui/{a:[^.]+}/{b:[^.]+}/{c:[^.]+}",
        "/ui/{a:[^.]+}/{b:[^.]+}/{c:[^.]+}/{d:[^.]+}",
    )
    @ResponseBody
    fun spaForward(): ResponseEntity<Resource> =
        ResponseEntity.ok()
            .contentType(MediaType.TEXT_HTML)
            .body(indexHtml)
}
