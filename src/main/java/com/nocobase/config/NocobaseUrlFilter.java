package com.nocobase.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 将 NocoBase 风格的 URL (/api/users:list) 重写为 Spring MVC 兼容格式 (/api/crud/users/list)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NocobaseUrlFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String path = httpRequest.getRequestURI();
        
        // 只处理 /api/ 路径，且不是已经被其他 Controller 处理的路径
        if (path.startsWith("/api/") && !isHandledByOtherController(path)) {
            // 去掉 /api/ 前缀
            String apiPath = path.substring("/api/".length());
            
            // 检查是否包含 :  (NocoBase 风格的 action)
            int colonIndex = apiPath.indexOf(':');
            if (colonIndex > 0) {
                String collection = apiPath.substring(0, colonIndex);
                String action = apiPath.substring(colonIndex + 1);
                
                // 重写为 /api/crud/{collection}/{action}
                String newPath = "/api/crud/" + collection + "/" + action;
                
                // 保留 query string
                String queryString = httpRequest.getQueryString();
                String fullPath = queryString != null ? newPath + "?" + queryString : newPath;
                
                httpRequest = new RewrittenRequest(httpRequest, fullPath);
            }
        }
        
        chain.doFilter(httpRequest, response);
    }

    private boolean isHandledByOtherController(String path) {
        // 这些路径由专用 Controller 处理，不需要重写
        return path.startsWith("/api/auth") ||
               path.startsWith("/api/bootstrap") ||
               path.startsWith("/api/users") ||
               path.startsWith("/api/roles") ||
               path.startsWith("/api/acl") ||
               path.startsWith("/api/systemSettings") ||
               path.startsWith("/api/applicationPlugins") ||
               path.startsWith("/api/collections") ||
               path.startsWith("/api/uiSchemaTemplates") ||
               path.startsWith("/api/uiSchemas") ||
               path.startsWith("/api/plugins") ||
               path.startsWith("/api/fields") ||
               path.startsWith("/api/dataSources") ||
               path.startsWith("/api/auditLogs") ||
               path.startsWith("/api/crud/");
    }

    private static class RewrittenRequest extends HttpServletRequestWrapper {
        private final String newPath;

        public RewrittenRequest(HttpServletRequest request, String newPath) {
            super(request);
            this.newPath = newPath;
        }

        @Override
        public String getRequestURI() {
            // 只返回路径，不包含 query string
            int qIndex = newPath.indexOf('?');
            return qIndex > 0 ? newPath.substring(0, qIndex) : newPath;
        }

        @Override
        public String getQueryString() {
            int qIndex = newPath.indexOf('?');
            return qIndex > 0 ? newPath.substring(qIndex + 1) : super.getQueryString();
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            url.append(getScheme()).append("://").append(getServerName()).append(":").append(getServerPort());
            url.append(newPath);
            return url;
        }
    }
}