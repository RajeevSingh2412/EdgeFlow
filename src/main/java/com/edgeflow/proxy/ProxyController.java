package com.edgeflow.proxy;

import com.edgeflow.metrics.ProxyMetrics;
import com.edgeflow.ratelimit.RateLimitService;
import com.edgeflow.routing.RouteResolver;
import com.edgeflow.routing.RouteResolver.ResolvedRoute;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Enumeration;

@RestController
public class ProxyController {

    private final RouteResolver routeResolver;
    private final RateLimitService rateLimitService;
    private final ProxyMetrics metrics;
    private final RestClient restClient;

    public ProxyController(RouteResolver routeResolver,
                           RateLimitService rateLimitService,
                           ProxyMetrics metrics) {
        this.routeResolver = routeResolver;
        this.rateLimitService = rateLimitService;
        this.metrics = metrics;
        this.restClient = RestClient.create();
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        Timer.Sample timer = metrics.startTimer();
        String path = request.getRequestURI();
        String queryString = request.getQueryString();
        String host = request.getServerName();
        String method = request.getMethod();

        var routeOpt = routeResolver.resolve(host, path);
        if (routeOpt.isEmpty()) {
            metrics.recordRequest(timer, "unknown", method, 404);
            return ResponseEntity.status(404)
                    .body("{\"error\": \"No route matched\"}".getBytes());
        }

        ResolvedRoute route = routeOpt.get();

        // Rate limiting check
        if (!rateLimitService.isAllowed(request, route.routeId())) {
            metrics.recordRequest(timer, route.pathPrefix(), method, 429);
            metrics.recordRateLimitRejection(route.pathPrefix(), "IP");
            return ResponseEntity.status(429)
                    .body("{\"error\": \"Rate limit exceeded\"}".getBytes());
        }

        String remainingPath = path.substring(route.pathPrefix().length());
        String targetUrl = route.upstreamUrl() + remainingPath;
        if (queryString != null) {
            targetUrl += "?" + queryString;
        }

        HttpHeaders forwardHeaders = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (headerName.equalsIgnoreCase("host")) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(headerName);
            while (values.hasMoreElements()) {
                forwardHeaders.add(headerName, values.nextElement());
            }
        }

        try {
            ResponseEntity<byte[]> response = restClient.method(HttpMethod.valueOf(method))
                    .uri(targetUrl)
                    .headers(h -> h.addAll(forwardHeaders))
                    .body(body != null ? body : new byte[0])
                    .retrieve()
                    .toEntity(byte[].class);

            HttpHeaders responseHeaders = new HttpHeaders();
            response.getHeaders().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("transfer-encoding")) {
                    responseHeaders.addAll(name, values);
                }
            });

            metrics.recordRequest(timer, route.pathPrefix(), method, response.getStatusCode().value());

            return ResponseEntity.status(response.getStatusCode())
                    .headers(responseHeaders)
                    .body(response.getBody());

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            metrics.recordRequest(timer, route.pathPrefix(), method, e.getStatusCode().value());
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAsByteArray());
        } catch (ResourceAccessException e) {
            metrics.recordRequest(timer, route.pathPrefix(), method, 502);
            metrics.recordUpstreamError(route.pathPrefix(), route.upstreamUrl(), "unreachable");
            return ResponseEntity.status(502)
                    .body(("{\"error\": \"Upstream unreachable: " + route.upstreamUrl() + "\"}").getBytes());
        }
    }
}
