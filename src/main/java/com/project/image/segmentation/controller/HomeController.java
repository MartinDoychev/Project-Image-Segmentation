package com.project.image.segmentation.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the home page. Thin controller: just routes to a Thymeleaf view.
 */
    @Controller
    public class HomeController {
    private static final Logger log = LoggerFactory.getLogger(HomeController.class);

    @GetMapping("/")
    public String index() {
        log.debug("Serving home page");
        return "index"; // templates/index.html
    }
}