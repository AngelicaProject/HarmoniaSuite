package com.harmoniasuite.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Static files revalidate on every load instead of being pinned by a manual version query.
 * Spring Boot serves /static/* without cache headers, so browsers would hold stale files
 * indefinitely once the Last-Modified heuristic cache kicks in. With no-cache the browser
 * always asks first (a cheap 304 on localhost) and gets fresh content the moment a file
 * changes — no ?v= bumps, no hard refresh after edits.
 */
@Component
public class NoCacheStaticFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isStatic(path)) {
            response.setHeader("Cache-Control", "no-cache");
        }
        chain.doFilter(request, response);
    }

    private static boolean isStatic(String path) {
        return path.equals("/") || path.equals("/index.html")
                || path.startsWith("/js/") || path.startsWith("/css/") || path.startsWith("/img/");
    }
}