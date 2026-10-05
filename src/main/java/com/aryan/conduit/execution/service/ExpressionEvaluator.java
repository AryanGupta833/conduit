package com.aryan.conduit.execution.service;

import com.aryan.conduit.execution.entity.ExpressionNode;
import com.aryan.conduit.workflow.dto.BinaryNode;
import com.aryan.conduit.workflow.dto.LiteralNode;
import com.aryan.conduit.workflow.dto.VariableNode;
import com.aryan.conduit.workflow.dto.UnaryNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import org.springframework.stereotype.Service;
@Service
public class ExpressionEvaluator {
    private com.aryan.conduit.observability.ConduitMetrics metrics;

    @Autowired(required = false)
    void setMetrics(com.aryan.conduit.observability.ConduitMetrics metrics) { this.metrics = metrics; }

    public Object evaluate(String expression, Map<String, Object> variables) {
        try {
            if (expression == null || expression.length() > 2048)
                throw new IllegalArgumentException("Expression exceeds the 2048 character limit");
            String source = expression.trim();
            if (source.startsWith("${") && source.endsWith("}")) source = source.substring(2, source.length() - 1).trim();
            Object result = evaluate(new ExpressionParser(new ExpressionLexer(source).tokenize()).parse(), variables);
            if (metrics != null) metrics.expressionEvaluated(true);
            return result;
        } catch (RuntimeException e) {
            if (metrics != null) metrics.expressionEvaluated(false);
            throw e;
        }
    }

    public Object evaluate(ExpressionNode node,Map<String,Object> variables){
        return evaluate(node, variables, 0);
    }

    private Object evaluate(ExpressionNode node, Map<String, Object> variables, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Expression evaluation depth exceeds 64");
        if(node instanceof LiteralNode literal){
            return literal.value();
        }
        if(node instanceof VariableNode variable){
            String name=variable.name();
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
            return current;
        }
        if (node instanceof UnaryNode unary) {
            Object operand = evaluate(unary.operand(), variables, depth + 1);
            if (!"!".equals(unary.operator()) || !(operand instanceof Boolean value))
                throw new IllegalArgumentException("Unary ! requires a boolean operand");
            return !value;
        }
        if(node instanceof BinaryNode binary){
            Object left=evaluate(binary.left(),variables, depth + 1);
            if ("&&".equals(binary.operator()) && Boolean.FALSE.equals(left)) return false;
            if ("||".equals(binary.operator()) && Boolean.TRUE.equals(left)) return true;
            Object right=evaluate(binary.right(),variables, depth + 1);

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
                default -> throw new IllegalArgumentException(
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
            throw new IllegalArgumentException(
                    "Expected numeric operand but got: "
                            + value.getClass().getSimpleName()
            );
        }

        return number.doubleValue();
    }
}
