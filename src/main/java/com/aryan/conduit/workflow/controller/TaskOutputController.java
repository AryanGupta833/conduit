package com.aryan.conduit.workflow.controller;


import com.aryan.conduit.execution.service.TaskOutputService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/output")
@RequiredArgsConstructor
public class TaskOutputController {
    private final TaskOutputService taskOutputService;

    @PostMapping("/test/{executionId}")
    public String test(@PathVariable Long executionId){
        Map<String,Object> output=new HashMap<>();

        output.put("customerId",123);
        output.put("paymentStatus","SUCCESS");
        taskOutputService.storeOutput(executionId,15L,output);

        return "saved";
    }

    @GetMapping("/{executionId}/{taskId}")
    public Map<String,Object> getOutput(@PathVariable Long executionId,@PathVariable Long taskId){
        return taskOutputService.getOutput(executionId,taskId);
    }
}
