package com.transformplatform.api.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

// ── UiController ──────────────────────────────────────────────────────────────
//
// Serves the React SPA shell for all /ui/** deep-link routes.
//
// Why this is needed:
//   Vite builds the SPA with React Router as the client-side router.
//   If a user navigates directly to http://localhost:8080/ui/profiles/123,
//   Spring Boot looks for a static file at static/ui/profiles/123/index.html
//   — which doesn't exist — and returns a 404.
//
//   This controller forwards those requests to /ui/index.html, which loads
//   the React bundle. React Router then reads the URL and renders the right page.
//
// What it does NOT forward:
//   The regex `^[^.]*$` matches paths that contain no dot character.
//   Static asset URLs always contain a dot (e.g. /ui/assets/index-abc123.js,
//   /ui/favicon.svg), so they fall through to Spring's default static handler
//   and are served normally. Only "page" routes (no dot) get forwarded.
//
// Route structure:
//   /            → redirect → /ui/
//   /ui/         → index.html  (SPA shell)
//   /ui/profiles → forwarded  → index.html (React Router handles it)
//   /ui/profiles/new → forwarded → index.html
//   /ui/assets/  → served directly as static file
//   /api/**      → handled by @RestController beans
//   /swagger-ui/ → handled by SpringDoc

@Controller
class UiController {

    /**
     * Redirect the bare root URL to the SPA base path.
     * http://localhost:8080/ → http://localhost:8080/ui/
     */
    @GetMapping("/")
    fun root(): String = "redirect:/ui/"

/**
     * SPA fallback: forward any /ui/ route that doesn't look like a static file
     * (no dot in the final path segment) to index.html.
     *
     * The two mappings cover:
     *   /ui/profiles          (top-level route, no trailing slash)
     *   /ui/profiles/123/edit (nested route)
*/
    @GetMapping(
        "/ui/{path:[^.]*}",
        "/ui/{path:[^.]*}/**",
    )
    fun spaForward(): String = "forward:/ui/index.html"
}
