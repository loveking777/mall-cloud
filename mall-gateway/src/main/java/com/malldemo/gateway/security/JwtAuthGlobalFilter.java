package com.malldemo.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 全局 JWT 鉴权过滤器。
 *
 * 工作流程：
 * 1. 公开接口直接放行（但会清掉客户端伪造的身份头）；
 * 2. 其他接口必须带 Authorization: Bearer <token>；
 * 3. 验签通过后，把 userId / username 写进请求头，转发给下游服务。
 *
 * 关键安全点：先删除客户端自己传的 X-User-Id，
 * 再写入网关校验过的值。否则任何人加一个请求头就能冒充别人。
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(JwtAuthGlobalFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 下游服务从这两个头里取当前登录用户。
     * 约定：只信任网关写入的值。
     */
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USERNAME_HEADER = "X-Username";

    private static final String UNAUTHORIZED_BODY =
            "{\"code\":401,\"message\":\"登录凭证无效或已过期，请重新登录\",\"data\":null}";

    private static final String INTERNAL_FORBIDDEN_BODY =
            "{\"code\":404,\"message\":\"接口不存在\",\"data\":null}";

    private final JwtService jwtService;

    public JwtAuthGlobalFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain) {

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 内部接口只允许服务之间直接调用，不允许从网关绕进来
        if (path.startsWith("/internal/")) {
            return writeError(
                    exchange,
                    HttpStatus.NOT_FOUND,
                    INTERNAL_FORBIDDEN_BODY
            );
        }

        // 公开接口：直接放行，但必须清掉客户端伪造的身份头
        if (isPublic(request, path)) {
            ServerHttpRequest cleaned = request.mutate()
                    .headers(headers -> {
                        headers.remove(USER_ID_HEADER);
                        headers.remove(USERNAME_HEADER);
                    })
                    .build();

            return chain.filter(
                    exchange.mutate().request(cleaned).build()
            );
        }

        String authorization = request.getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        if (authorization == null
                || !authorization.startsWith(BEARER_PREFIX)
                || authorization.substring(BEARER_PREFIX.length())
                .isBlank()) {
            return writeError(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    UNAUTHORIZED_BODY
            );
        }

        String token = authorization
                .substring(BEARER_PREFIX.length())
                .trim();

        Claims claims;
        Long userId;

        try {
            claims = jwtService.parseClaims(token);

            String subject = claims.getSubject();

            if (subject == null || subject.isBlank()) {
                return writeError(
                        exchange,
                        HttpStatus.UNAUTHORIZED,
                        UNAUTHORIZED_BODY
                );
            }

            userId = Long.valueOf(subject);

        } catch (JwtException | IllegalArgumentException ex) {
            // 签名错误、过期、格式错误、subject 不是数字
            log.debug("JWT 校验失败：{}", ex.getMessage());

            return writeError(
                    exchange,
                    HttpStatus.UNAUTHORIZED,
                    UNAUTHORIZED_BODY
            );
        }

        String username = claims.get("username", String.class);

        // 删除客户端可能伪造的头，再写入可信值
        ServerHttpRequest mutated = request.mutate()
                .headers(headers -> {
                    headers.remove(USER_ID_HEADER);
                    headers.remove(USERNAME_HEADER);
                    headers.set(USER_ID_HEADER, userId.toString());

                    if (username != null && !username.isBlank()) {
                        headers.set(USERNAME_HEADER, username);
                    }
                })
                .build();

        return chain.filter(
                exchange.mutate().request(mutated).build()
        );
    }

    /**
     * 公开接口白名单。
     *
     * 注册、登录不需要 token；商品浏览（GET）不需要 token。
     * 商品的写操作（POST/PUT/DELETE）仍然要求登录。
     */
    private boolean isPublic(
            ServerHttpRequest request,
            String path) {

        HttpMethod method = request.getMethod();

        if (HttpMethod.POST.equals(method)
                && ("/user/register".equals(path)
                || "/user/login".equals(path))) {
            return true;
        }

        return HttpMethod.GET.equals(method)
                && ("/product".equals(path)
                || path.startsWith("/product/"));
    }

    private Mono<Void> writeError(
            ServerWebExchange exchange,
            HttpStatus status,
            String body) {

        ServerHttpResponse response = exchange.getResponse();

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 越小越先执行。放在路由转发之前。
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
