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
        if (input == null || input.length() > 2048) {
            throw new IllegalArgumentException("Expression is empty or exceeds the 2048 character limit");
        }
        List<Token> tokens = new ArrayList<>();

        while (!isAtEnd()) {
            if (tokens.size() >= 256) throw new IllegalArgumentException("Expression exceeds the 256 token limit");
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

            if (Character.isLetterOrDigit(current) || current == '_' || current == '.' || current == '-') {
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
        if(value.equals("null")){
            return new Token(TokenType.NULL,value);
        }
        return new Token(TokenType.IDENTIFIER,value);


    }
    private Token readNumber(){
        StringBuilder builder=new StringBuilder();
        boolean decimal = false;

        while (!isAtEnd()){
            char current=peek();
            if(Character.isDigit(current)||(current=='.' && !decimal)){
                if (current == '.') decimal = true;
                builder.append(advance());
            }
            else{
                break;
            }
        }
        if (builder.isEmpty() || builder.toString().equals(".")) throw new IllegalArgumentException("Invalid number literal");
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
            char next = advance();
            if (next == '\\') {
                if (isAtEnd()) throw new IllegalArgumentException("Invalid escape in string literal");
                char escaped = advance();
                next = switch (escaped) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case '"' -> '"'; case '\\' -> '\\'; default -> throw new IllegalArgumentException("Unsupported string escape"); };
            }
            builder.append(next);
            if (builder.length() > 1024) throw new IllegalArgumentException("Expression string literal exceeds 1024 characters");
        }
        if(isAtEnd()){
            throw new RuntimeException("Unterminated string literal");
        }
        advance();
        return new Token(TokenType.STRING, builder.toString())
;    }

}
