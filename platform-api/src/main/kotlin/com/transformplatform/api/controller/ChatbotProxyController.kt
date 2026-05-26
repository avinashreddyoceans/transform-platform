package com.transformplatform.api.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

/**
 * //Transparent reverse-proxy that forwards /chatbot/ → the Python chatbot service.
 *
 * This lets the built React app (served from :8080/ui/) reach the chatbot at
 * /chatbot/sessions without needing a separate Vite dev-server proxy.
 *
 * CHATBOT_URL defaults to http://localhost:8000.
 */

@RestController
@RequestMapping("/chatbot")
class ChatbotProxyController(
    @Value("\${chatbot.url:http://localhost:8000}") private val chatbotUrl: String,
) {
    private val restTemplate = RestTemplate()

    @RequestMapping("/**")
    fun proxy(
        request: HttpServletRequest,
        @RequestBody(required = false) body: ByteArray?,
        method: HttpMethod,
    ): ResponseEntity<ByteArray> {
        val path = request.requestURI.removePrefix("/chatbot")
        val query = request.queryString?.let { "?$it" } ?: ""
        val targetUrl = UriComponentsBuilder
            .fromUriString("$chatbotUrl$path$query")
            .build(true).toUri()

        val headers = HttpHeaders()
        request.headerNames.asSequence().forEach { name ->
            if (!name.equals("host", ignoreCase = true)) {
                headers[name] = request.getHeaders(name).toList()
            }
        }

        val entity = HttpEntity(body, headers)
        return restTemplate.exchange(targetUrl, method, entity, ByteArray::class.java)
    }
}
