package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.TokenType;
import com.aryan.conduit.workflow.dto.Token;

import java.util.ArrayList;
import java.util.List;

public class ExpressionLexer {
    private final String input;
    private int position = 0;

    public ExpressionLexer(String input) {
        this.input = input;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();

        while (!isAtEnd()) {
            char current = peek();

            if (current == '"') {
                tokens.add(readString());
                continue;
            }

            if (Character.isWhitespace(current)) {
                advance();
                continue;
            }

            if (Character.isLetter(current) || current == '_') {
                tokens.add(readIdentifier());
                continue;
            }
            if (Character.isDigit(current)) {
                tokens.add(readNumber());
                continue;
            }

            switch (current){
                case '=' ->{
                    if(peekNext()=='='){
                        advance();
                        advance();

                        tokens.add(new Token(TokenType.EQ,"=="));
                        continue;
                    }

                }
                case '!'->{
                    if(peekNext()=='='){
                        advance();
                        advance();

                        tokens.add(new Token(TokenType.NE,"!="));
                    }
                    else{
                        advance();
                        tokens.add(new Token(TokenType.NOT,"!"));
                    }
                    continue;
                }
                case '>'->{
                    if(peekNext()=='='){
                        advance();
                        advance();
                        tokens.add(new Token(TokenType.GTE,">="));
                    }
                    else{
                        advance();
                        tokens.add(new Token(TokenType.GT,">"));
                    }
                    continue;
                }
                case '<'->{
                    if(peekNext()=='='){
                        advance();
                        advance();

                        tokens.add(new Token(TokenType.LTE,"<="));
                    }
                    else{
                        advance();
                        tokens.add(new Token(TokenType.LT,"<"));
                    }
                    continue;
                }
                case '&'->{
                    if(peekNext()=='&'){
                        advance();
                        advance();

                        tokens.add(new Token(TokenType.AND,"&&"));
                        continue;
                    }
                }
                case '|'->{
                    if(peekNext()=='|'){
                        advance();
                        advance();

                        tokens.add(new Token(TokenType.OR,"||"));
                    continue;
                    }

                }
                case '('->{
                    advance();
                    tokens.add(new Token(TokenType.LPAREN,"("));
                    continue;
                }
                case ')'->{
                    advance();
                    tokens.add(new Token(TokenType.RPAREN,")"));
                    continue;
                }


            }

            throw new RuntimeException("Unexpected character : " + current);
        }
        tokens.add(new Token(TokenType.EOF, ""));

        return tokens;
    }

    private boolean isAtEnd() {
        return position >= input.length();
    }

    private char peek() {
        return input.charAt(position);
    }

    private char advance() {
        return input.charAt(position++);
    }

    private Token readIdentifier() {
        StringBuilder builder = new StringBuilder();
        while (!isAtEnd()) {
            char current = peek();

            if (Character.isLetterOrDigit(current) || current == '_' || current == '.') {
                builder.append(advance());
            } else {
                break;
            }

        }
        String value= builder.toString();

        if(value.equals("true")){
            return new Token(TokenType.TRUE,value);
        }
        if(value.equals("false")){
            return new Token(TokenType.FALSE,value);
        }
        return new Token(TokenType.IDENTIFIER,value);


    }
    private Token readNumber(){
        StringBuilder builder=new StringBuilder();

        while (!isAtEnd()){
            char current=peek();
            if(Character.isDigit(current)||current=='.'){
                builder.append(advance());
            }
            else{
                break;
            }
        }
        return new Token(TokenType.NUMBER, builder.toString());
    }

    private char peekNext(){
        if(position+1>=input.length()){
            return '\0';
        }
        return input.charAt(position+1);
    }

    private Token readString(){
        advance();
        StringBuilder builder=new StringBuilder();
        while (!isAtEnd()&&peek()!='"'){
            builder.append(advance());
        }
        if(isAtEnd()){
            throw new RuntimeException("Unterminated string literal");
        }
        advance();
        return new Token(TokenType.STRING, builder.toString())
;    }

}