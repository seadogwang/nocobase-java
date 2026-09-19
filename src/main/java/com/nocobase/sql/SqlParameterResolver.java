package com.nocobase.sql;

import com.nocobase.acl.CurrentUserContext;
import com.nocobase.web.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves parameter values from {@link SqlParameterMetadata} for a list of parameter names.
 *
 * <p>Supports parameter sources:
 * <ul>
 *   <li>{@code static} -- value comes from {@code defaultValue} in the metadata</li>
 *   <li>{@code currentUser} -- value comes from {@link CurrentUserContext}, resolved by {@code path}</li>
 * </ul>
 *
 * <p>For {@code currentUser} parameters:
 * <ul>
 *   <li>{@code path: "id"} -- resolves to the current authenticated user's ID ({@link Long})</li>
 *   <li>{@code path: "email"} -- resolves to the current authenticated user's email ({@link String})</li>
 *   <li>If the parameter is {@code required} and the user is anonymous, throws {@link UnauthorizedException}</li>
 * </ul>
 */
@Component
public class SqlParameterResolver {

    private static final Logger log = LoggerFactory.getLogger(SqlParameterResolver.class);

    private final CurrentUserContext currentUserContext;

    public SqlParameterResolver(CurrentUserContext currentUserContext) {
        this.currentUserContext = currentUserContext;
    }

    /**
     * Resolve values for a list of parameter names, in order.
     *
     * @param metadata   the parsed parameter metadata
     * @param paramNames ordered list of parameter names from SQL parsing
     * @return ordered list of resolved values
     * @throws IllegalArgumentException if a parameter name is not found in metadata
     * @throws UnauthorizedException    if a required currentUser parameter cannot be resolved
     */
    public List<Object> resolve(SqlParameterMetadata metadata, List<String> paramNames) {
        List<Object> values = new ArrayList<>();
        for (String name : paramNames) {
            SqlParameterMetadata.ParameterDef param = metadata.getParameter(name);
            if (param == null) {
                throw new IllegalArgumentException(
                        "SQL references parameter '" + name + "' which is not defined in collection options");
            }
            values.add(resolveValue(param));
        }
        return values;
    }

    /**
     * Resolve a single parameter value based on its source.
     */
    private Object resolveValue(SqlParameterMetadata.ParameterDef param) {
        String source = param.getSource();
        if ("static".equals(source)) {
            return param.getDefaultValue();
        }
        if ("currentUser".equals(source)) {
            return resolveCurrentUser(param);
        }
        throw new IllegalArgumentException(
                "Unsupported parameter source '" + source + "' for parameter '" + param.getName() + "'");
    }

    /**
     * Resolve a currentUser parameter from the current security context.
     */
    private Object resolveCurrentUser(SqlParameterMetadata.ParameterDef param) {
        String path = param.getPath();
        Object value = switch (path) {
            case "id" -> currentUserContext.getCurrentUserId().orElse(null);
            case "email" -> currentUserContext.getCurrentUserEmail().orElse(null);
            default -> throw new IllegalArgumentException(
                    "Unknown currentUser path '" + path + "' for parameter '" + param.getName() + "'");
        };

        if (value == null && param.isRequired()) {
            throw new UnauthorizedException(
                    "currentUser parameter '" + param.getName() + "' requires authentication but user is anonymous");
        }

        return value;
    }
}