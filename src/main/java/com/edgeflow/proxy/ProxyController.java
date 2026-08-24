package com.edgeflow.proxy;

import com.edgeflow.config.RouteConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
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

    private final RouteConfig routeConfig;
    private final RestClient restClient;

    public ProxyController(RouteConfig routeConfig) {
        this.routeConfig = routeConfig;
        this.restClient = RestClient.create();
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();
        String queryString = request.getQueryString();

        var routeOpt = routeConfig.findRoute(path);
        if (routeOpt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body("{\"error\": \"No route matched\"}".getBytes());
        }

        RouteConfig.Route route = routeOpt.get();
        String remainingPath = path.substring(route.getPathPrefix().length());
        String targetUrl = route.getUpstream() + remainingPath;
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
            ResponseEntity<byte[]> response = restClient.method(HttpMethod.valueOf(request.getMethod()))
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

            return ResponseEntity.status(response.getStatusCode())
                    .headers(responseHeaders)
                    .body(response.getBody());

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(e.getResponseBodyAsByteArray());
        } catch (ResourceAccessException e) {
            return ResponseEntity.status(502)
                    .body(("{\"error\": \"Upstream unreachable: " + route.getUpstream() + "\"}").getBytes());
        }
    }
}