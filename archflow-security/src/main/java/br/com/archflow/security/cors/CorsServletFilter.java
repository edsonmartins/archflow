package br.com.archflow.security.cors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Servlet filter that applies CORS configuration based on the current environment.
 *
 * <p>This filter:
 * <ul>
 *   <li>Automatically detects the environment using {@link EnvironmentResolver}</li>
 *   <li>Applies appropriate CORS headers based on the environment</li>
 *   <li>Handles preflight OPTIONS requests</li>
 *   <li>Logs CORS violations for debugging</li>
 * </ul>
 *
 * <p>Usage in web.xml:</p>
 * <pre>
 * &lt;filter&gt;
 *   &lt;filter-name&gt;corsFilter&lt;/filter-name&gt;
 *   &lt;filter-class&gt;br.com.archflow.security.cors.CorsServletFilter&lt;/filter-class&gt;
 * &lt;/filter&gt;
 * &lt;filter-mapping&gt;
 *   &lt;filter-name&gt;corsFilter&lt;/filter-name&gt;
 *   &lt;url-pattern&gt;/*&lt;/url-pattern&gt;
 * &lt;/filter-mapping&gt;
 * </pre>
 *
 * <p>Or programmatically:</p>
 * <pre>
 * CorsServletFilter filter = new CorsServletFilter();
 * // registration with servlet container
 * </pre>
 */
public class CorsServletFilter implements jakarta.servlet.Filter {

    private static final Logger log = LoggerFactory.getLogger(CorsServletFilter.class);

    private final CorsConfiguration configuration;
    private final CorsConfiguration.CorsFilter corsFilter;

    /**
     * Creates a CORS filter that auto-detects the environment.
     */
    public CorsServletFilter() {
        String environment = EnvironmentResolver.getEnvironment();
        this.configuration = CorsConfiguration.forEnvironment(environment);
        this.corsFilter = new CorsConfiguration.CorsFilter(configuration);
        log.info("CORS filter initialized for environment: {}", environment);
    }

    /**
     * Creates a CORS filter with a specific configuration.
     *
     * @param configuration The CORS configuration to use
     */
    public CorsServletFilter(CorsConfiguration configuration) {
        this.configuration = configuration;
        this.corsFilter = new CorsConfiguration.CorsFilter(configuration);
        log.info("CORS filter initialized with custom configuration");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest) ||
            !(response instanceof HttpServletResponse httpResponse)) {

            chain.doFilter(request, response);
            return;
        }

        String origin = httpRequest.getHeader("Origin");
        String method = httpRequest.getMethod();

        // Create response adapter
        CorsResponseAdapter responseAdapter = new CorsResponseAdapter(httpResponse);

        // Apply CORS headers
        if (origin != null && !origin.isEmpty()) {
            if (configuration.isOriginAllowed(origin)) {
                corsFilter.applyCorsHeaders(origin, method, responseAdapter);

                // Handle preflight request
                if (corsFilter.isPreflightRequest(method)) {
                    log.debug("CORS preflight request allowed from origin: {}", origin);
                    httpResponse.setStatus(HttpServletResponse.SC_OK);
                    return;
                }

                log.trace("CORS headers applied for origin: {}, method: {}", origin, method);
            } else {
                log.warn("CORS request denied for origin: {}, method: {}", origin, method);
                // Origin not allowed - don't set CORS headers, let browser handle it
            }
        }

        // Continue filter chain
        chain.doFilter(request, response);
    }

    /**
     * Gets the CORS configuration used by this filter.
     */
    public CorsConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Adapter for HttpServletResponse to CorsResponse interface.
     */
    private static class CorsResponseAdapter implements CorsConfiguration.CorsResponse {
        private final HttpServletResponse response;

        CorsResponseAdapter(HttpServletResponse response) {
            this.response = response;
        }

        @Override
        public void setHeader(String name, String value) {
            response.setHeader(name, value);
        }
    }
}
