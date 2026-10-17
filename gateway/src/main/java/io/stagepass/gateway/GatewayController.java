package io.stagepass.gateway;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
public class GatewayController {
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "proxy-connection",
        "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-port", "x-forwarded-prefix", "x-forwarded-proto", "x-real-ip");
    private final GatewayProperties properties;
    private final RestClient client;
    private final List<String> bookingNodes;
    private final AtomicInteger nextNode = new AtomicInteger();

    public GatewayController(GatewayProperties properties) {
        this.properties = properties;
        this.bookingNodes = List.of(properties.getBookingNodes().split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @RequestMapping("/api/auth/**")
    public ResponseEntity<byte[]> auth(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return forward(request, body, "/api/auth", properties.getAuthBaseUrl());
    }

    @RequestMapping("/api/catalog/**")
    public ResponseEntity<byte[]> catalog(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        return forward(request, body, "/api/catalog", properties.getCatalogBaseUrl());
    }

    @RequestMapping("/api/booking/**")
    public ResponseEntity<byte[]> booking(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        if (bookingNodes.isEmpty()) return gatewayError(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_UNAVAILABLE");
        int nodeIndex = Math.floorMod(nextNode.getAndIncrement(), bookingNodes.size());
        return forward(request, body, "/api/booking", bookingNodes.get(nodeIndex));
    }

    private ResponseEntity<byte[]> forward(HttpServletRequest request, byte[] body, String prefix, String baseUrl) {
        String requestId = UUID.randomUUID().toString();
        try {
            String suffix = request.getRequestURI().substring(prefix.length());
            if (suffix.isEmpty()) suffix = "/";
            if (prefix.equals("/api/auth") && (suffix.equals("/login") || suffix.equals("/register"))) suffix = "/auth" + suffix;
            String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
            URI uri = URI.create(baseUrl.replaceAll("/$", "") + suffix + query);
            HttpMethod method = HttpMethod.valueOf(request.getMethod());
            byte[] payload = body == null ? new byte[0] : body;
            return client.method(method).uri(uri).headers(headers -> copyRequestHeaders(request, headers, requestId))
                .body(payload).exchange((outgoing, incoming) -> {
                    HttpHeaders headers = new HttpHeaders();
                    incoming.getHeaders().forEach((name, values) -> {
                        if (!HOP_BY_HOP.contains(name.toLowerCase())) headers.addAll(name, values);
                    });
                    headers.set("X-Request-Id", requestId);
                    return ResponseEntity.status(incoming.getStatusCode()).headers(headers).body(incoming.getBody().readAllBytes());
                });
        } catch (RestClientException | IllegalArgumentException error) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("X-Request-Id", requestId).body("{\"code\":\"UPSTREAM_UNAVAILABLE\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpHeaders headers, String requestId) {
        var names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase()) || name.equalsIgnoreCase("x-request-id")) continue;
            var values = request.getHeaders(name);
            while (values.hasMoreElements()) headers.add(name, values.nextElement());
        }
        headers.set("X-Request-Id", requestId);
    }

    private ResponseEntity<byte[]> gatewayError(HttpStatus status, String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "application/json")
            .body(("{\"code\":\"" + code + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
