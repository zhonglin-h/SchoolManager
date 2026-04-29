package com.school.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards all unmatched GET requests (no extension, not under /api) to
 * index.html so that React Router deep-links work when the app is served as
 * static files from Spring Boot.
 */
@Controller
public class SpaController {

    @GetMapping(value = {"/{path:[^\\.]*}", "/{path:[^\\.]*}/**"})
    public String forward() {
        return "forward:/index.html";
    }
}
