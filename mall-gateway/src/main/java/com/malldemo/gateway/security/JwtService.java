package com.malldemo.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;

/**
 * 网关侧的 JWT 校验服务。
 *
 * 它只做"验签 + 取声明"，不签发 token。
 * 密钥、算法、issuer 必须和 mall-user-service 完全一致：
 * - 密钥：环境变量 MALL_JWT_SECRET_BASE64
 * - issuer：mall-cloud
 */
@Service
public class JwtService {

    private static final String ISSUER = "mall-cloud";

    private final SecretKey signingKey;

    public JwtService(
            @Value("${mall.jwt.secret-base64:}") String secretBase64
    ) {
        if (secretBase64 == null || secretBase64.isBlank()) {
            throw new IllegalStateException(
                    "未配置 JWT 密钥：请设置环境变量 MALL_JWT_SECRET_BASE64"
                            + "（Base64 编码，解码后至少 32 字节）"
            );
        }

        byte[] keyBytes = Decoders.BASE64.decode(secretBase64);

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 校验签名、过期时间和 issuer，并返回载荷。
     *
     * 校验失败会抛 JwtException（或 IllegalArgumentException），
     * 由过滤器统一转成 401。
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
