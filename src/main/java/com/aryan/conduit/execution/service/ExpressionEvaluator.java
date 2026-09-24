package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExpressionNode;
import com.aryan.conduit.workflow.dto.BinaryNode;
import com.aryan.conduit.workflow.dto.LiteralNode;
import com.aryan.conduit.workflow.dto.VariableNode;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service
public class ExpressionEvaluator {
    public Object evaluate(ExpressionNode node,Map<String,Object> variables){
        if(node instanceof LiteralNode literal){
            return literal.value();
        }
        if(node instanceof VariableNode variable){
            String name=variable.name();
            System.out.println("Resolving variable "+name);
            if(!name.contains(".")){
                return variables.get(name);
            }
            String[] parts=name.split("\\.");
            Object current=variables;

            for(String part:parts){
                if(current instanceof Map<?,?> map){
                    current=map.get(part);
                }
                else {
                    return null;
                }
            }
            System.out.println("Resolved value "+current);
            return current;
        }
        if(node instanceof BinaryNode binary){
            Object left=evaluate(binary.left(),variables);
            Object right=evaluate(binary.right(),variables);

            return evaluateBinary(left,right,binary.operator());
        }
        throw new RuntimeException("Unknown node type: "+node.getClass().getSimpleName());
    }

    private Object evaluateBinary(
            Object left,
            Object right,
            String operator
    ) {

        if (left == null || right == null) {

            return switch (operator) {
                case "==" -> left == right;
                case "!=" -> left != right;
                default -> throw new RuntimeException(
                        operator + " cannot be used with null operands"
                );
            };
        }

        switch (operator) {

            case "==" -> {
                if (isNumeric(left) && isNumeric(right)) {
                    return toDouble(left) == toDouble(right);
                }

                return left.toString().equals(right.toString());
            }

            case "!=" -> {
                if (isNumeric(left) && isNumeric(right)) {
                    return toDouble(left) != toDouble(right);
                }

                return !left.toString().equals(right.toString());
            }

            case ">" -> {
                return toDouble(left) > toDouble(right);
            }

            case "<" -> {
                return toDouble(left) < toDouble(right);
            }

            case ">=" -> {
                return toDouble(left) >= toDouble(right);
            }

            case "<=" -> {
                return toDouble(left) <= toDouble(right);
            }

            case "&&" -> {

                if (!(left instanceof Boolean)
                        || !(right instanceof Boolean)) {

                    throw new RuntimeException(
                            "&& requires boolean operands"
                    );
                }

                return (Boolean) left && (Boolean) right;
            }

            case "||" -> {

                if (!(left instanceof Boolean)
                        || !(right instanceof Boolean)) {

                    throw new RuntimeException(
                            "|| requires boolean operands"
                    );
                }

                return (Boolean) left || (Boolean) right;
            }

            default -> throw new RuntimeException(
                    "Unsupported operator: " + operator
            );
        }
    }

    private boolean isNumeric(Object value) {
        return value instanceof Number;
    }

    private double toDouble(Object value) {
        if (!(value instanceof Number number)) {
            throw new RuntimeException(
                    "Expected numeric operand but got: "
                            + value.getClass().getSimpleName()
            );
        }

        return number.doubleValue();
    }
}
