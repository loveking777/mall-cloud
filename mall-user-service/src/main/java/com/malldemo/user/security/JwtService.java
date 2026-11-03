package com.malldemo.user.security;

import com.malldemo.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发服务。
 *
 * 重要约定（网关必须完全一致，否则验签失败）：
 * 1. 密钥来自环境变量 MALL_JWT_SECRET_BASE64（Base64 编码，解码后至少 32 字节）；
 * 2. 算法 HS256（由密钥长度决定）；
 * 3. issuer = mall-cloud；
 * 4. subject = 用户 id，另外附带 username 声明。
 */
@Service
public class JwtService {

    private static final String ISSUER = "mall-cloud";

    private final SecretKey signingKey;

    @Getter
    private final long expirationSeconds;

    public JwtService(
            @Value("${mall.jwt.secret-base64:}") String secretBase64,
            @Value("${mall.jwt.expiration-seconds:7200}")
            long expirationSeconds
    ) {
        if (secretBase64 == null || secretBase64.isBlank()) {
            throw new IllegalStateException(
                    "未配置 JWT 密钥：请设置环境变量 MALL_JWT_SECRET_BASE64"
                            + "（Base64 编码，解码后至少 32 字节）"
            );
        }

        byte[] keyBytes = Decoders.BASE64.decode(secretBase64);

        // 密钥过短会直接抛异常，避免使用弱密钥
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationSeconds = expirationSeconds;
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(
                        now.plusSeconds(expirationSeconds)
                ))
                .signWith(signingKey)
                .compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
