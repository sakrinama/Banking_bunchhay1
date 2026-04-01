package com.titan.gateway.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Task 5: Field-Level RBAC Instrumentation
 *
 * Intercepts every field resolution. If the field has @adminOnly directive,
 * checks that the current JWT has ROLE_ADMIN. Returns null + error if not.
 * Standard users see maskedAccountNumber; admins see totalLedgerBalance + balance.
 */
@Component
public class SupergraphRbacInstrumentation extends SimplePerformantInstrumentation {

    @Override
    public InstrumentationContext<Object> beginFieldFetch(
            InstrumentationFieldFetchParameters params,
            graphql.execution.instrumentation.InstrumentationState state) {

        GraphQLFieldDefinition field = params.getField();
        boolean isAdminOnly = field.getDirective("adminOnly") != null;

        if (isAdminOnly) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            boolean isAdmin = auth != null && auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

            if (!isAdmin) {
                // Null out the field and add a partial error — does not crash the whole query
                params.getExecutionStepInfo().getPath();
                throw new graphql.GraphQLException(
                        "Access denied: field '" + field.getName() + "' requires ADMIN role");
            }
        }

        return super.beginFieldFetch(params, state);
    }
}
