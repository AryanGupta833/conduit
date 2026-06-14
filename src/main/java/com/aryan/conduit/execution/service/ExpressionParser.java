package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExpressionNode;
import com.aryan.conduit.execution.entity.TokenType;
import com.aryan.conduit.workflow.dto.BinaryNode;
import com.aryan.conduit.workflow.dto.LiteralNode;
import com.aryan.conduit.workflow.dto.Token;
import com.aryan.conduit.workflow.dto.VariableNode;

import java.util.List;

public class ExpressionParser {
    private final List<Token> tokens;
    private int position=0;

    public ExpressionParser(List<Token> tokens){
        this.tokens=tokens;
    }
    private Token peek(){
        return tokens.get(position);
    }
    private Token advance(){
        return tokens.get(position++);
    }
    private boolean match(TokenType type){
        if(peek().type()==type){
            advance();
            return true;
        }
        return false;
    }

    public ExpressionNode parse(){
        return expression();
    }

    private ExpressionNode expression(){
        return or();
    }

    private ExpressionNode or(){
        ExpressionNode left=and();
        while (match(TokenType.OR)){
            Token operator=tokens.get(position-1);

            ExpressionNode right=and();
            left=new BinaryNode(left,operator.value(),right);
        }
        return left;
    }

    private ExpressionNode and(){
        ExpressionNode left=equality();
        while (match(TokenType.AND)){
            Token operator=tokens.get(position-1);
            ExpressionNode right=equality();

            left=new BinaryNode(left, operator.value(),right);

        }
        return left;
    }

    private ExpressionNode equality(){
        ExpressionNode left=comparison();

        while (true){
            if(match(TokenType.EQ)){
                Token operator=tokens.get(position-1);
                ExpressionNode right=comparison();
                left=new BinaryNode(left, operator.value(),right);
                continue;
            }
            if(match(TokenType.NE)){
                Token operator=tokens.get(position-1);
                ExpressionNode right=comparison();
                left=new BinaryNode(left, operator.value(),right);
                continue;
            }
            break;
        }
        return left;
    }

    private ExpressionNode comparison(){
        ExpressionNode left=primary();

        while (true){
            if(match(TokenType.GT)||match(TokenType.LT)||match(TokenType.GTE)||match(TokenType.LTE)){
                Token operator=tokens.get(position-1);
                ExpressionNode right=primary();

                left=new BinaryNode(left, operator.value(), right);
                continue;
            }
            break;
        }
        return left;
    }
    private ExpressionNode primary(){
        Token token=advance();
        switch (token.type()){
            case NUMBER -> {
                return new LiteralNode(Double.parseDouble(token.value()));
            }
            case TRUE ->{
                return new LiteralNode(true);
            }
            case FALSE -> {
                return new LiteralNode(false);
            }
            case IDENTIFIER ->{
                return new VariableNode(token.value());
            }
            case LPAREN -> {
                ExpressionNode expr=expression();
                if(!match(TokenType.RPAREN)){
                    throw new RuntimeException("Missing ')'");
                }
                return expr;
            }
            case STRING -> {
                return new LiteralNode(token.value());
            }
            default -> throw new RuntimeException("Unexpected token "+token);
        }
    }

}
