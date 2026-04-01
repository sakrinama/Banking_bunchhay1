package com.titan.promotions.engine;

import com.titan.promotions.event.TransactionCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RuleEngine {
    
    private final ExpressionParser parser = new SpelExpressionParser();
    
    public boolean evaluate(String ruleExpression, TransactionCompletedEvent event) {
        try {
            StandardEvaluationContext context = new StandardEvaluationContext(event);
            context.setVariable("transactionAmount", event.getAmount());
            context.setVariable("currency", event.getCurrency());
            context.setVariable("transactionType", event.getTransactionType());
            context.setVariable("accountId", event.getAccountId());
            context.setVariable("metadata", event.getMetadata());
            
            Boolean result = parser.parseExpression(ruleExpression).getValue(context, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.error("Rule evaluation failed for expression: {}", ruleExpression, e);
            return false;
        }
    }
}
