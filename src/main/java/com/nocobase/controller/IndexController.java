package com.nocobase.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Controller;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/")
public class IndexController {

    private static final String INDEX_HTML_PATH = "/static/v/index.html";

    @GetMapping(value = {"/", "/**"})
    public void serveIndex(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        
        // API requests should not be handled here
        if (path.startsWith("/api/")) {
            response.sendError(404, "API endpoint not found");
            return;
        }
        
        // Check if the requested resource exists as a static file
        String resourcePath = "/static" + path;
        ClassPathResource resource = new ClassPathResource(resourcePath);
        
        if (resource.exists()) {
            // Serve the static file directly
            try (InputStream is = resource.getInputStream()) {
                StreamUtils.copy(is, response.getOutputStream());
            }
            return;
        }
        
        // For SPA routing, serve index.html with injected runtime config
        serveIndexWithConfig(response);
    }

    private void serveIndexWithConfig(HttpServletResponse response) throws IOException {
        ClassPathResource indexResource = new ClassPathResource(INDEX_HTML_PATH);
        
        if (!indexResource.exists()) {
            response.sendError(404, "Frontend not found. Run 'yarn build' first.");
            return;
        }
        
        String html;
        try (InputStream is = indexResource.getInputStream()) {
            html = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        }
        
        // Inject runtime configuration
        html = injectRuntimeConfig(html);
        
        response.setContentType("text/html; charset=UTF-8");
        response.getWriter().write(html);
    }

    private String injectRuntimeConfig(String html) {
        String config = """
                <script>
                window.__NOCOBASE_CONFIG__ = {
                    apiBaseURL: '/api',
                    appName: 'main',
                    apiPrefix: '/api'
                };
                </script>
                """;
        
        // Insert before the module script tag or before </head>
        if (html.contains("<script type=\"module\"")) {
            html = html.replaceFirst("<script type=\"module\"", config + "\n<script type=\"module\"");
        } else if (html.contains("</head>")) {
            html = html.replace("</head>", config + "\n</head>");
        } else {
            html = config + html;
        }
        
        return html;
    }
}
