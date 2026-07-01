package com.aryan.conduit.workflow.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CircuitController {
    @GetMapping("/circuit")
    public String circuit(){
        return "check logs";
    }
}
