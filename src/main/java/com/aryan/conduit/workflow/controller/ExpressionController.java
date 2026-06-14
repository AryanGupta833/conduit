package com.aryan.conduit.workflow.controller;


import com.aryan.conduit.execution.entity.ExpressionNode;
import com.aryan.conduit.execution.service.ExpressionEvaluator;
import com.aryan.conduit.execution.service.ExpressionLexer;
import com.aryan.conduit.execution.service.ExpressionParser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/expression")
@RequiredArgsConstructor
public class ExpressionController {
    private final ExpressionEvaluator expressionEvaluator;

    @GetMapping("/test")
    public Object test(){
        ExpressionLexer lexer=new ExpressionLexer("");
        return lexer.tokenize();
    }

    @GetMapping("/parse")
    public Object parse(){
        ExpressionLexer lexer=new ExpressionLexer("amount > 1000 && prediction == BUY");
        ExpressionParser parser=new ExpressionParser(lexer.tokenize());
        return parser.parse();
    }

    @GetMapping("/evaluate")
    public Object evaluate(){
        ExpressionLexer lexer=new ExpressionLexer("prediction == \"BUY\"");
        ExpressionParser parser=new ExpressionParser(lexer.tokenize());
        ExpressionNode ast= parser.parse();
        Map<String,Object> variables=new HashMap<>();
        variables.put("prediction","SELL");
        return expressionEvaluator.evaluate(ast,variables);
    }

}
