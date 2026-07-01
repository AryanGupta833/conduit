package com.aryan.conduit.execution.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;

@Service
public class CircuitBreakerTaskService {
    @CircuitBreaker(name="taskExecution",fallbackMethod = "fallback")
    public void executeTask(Runnable runnable){
        System.out.println("Circuit allows execution");
        runnable.run();
    }
    private void fallback(Runnable runnable,Exception ex){
        System.out.println("Circuit Breaker Open: "+ex.getClass().getSimpleName());
        throw new RuntimeException("Circuit Breaker Open",ex);
    }
}
