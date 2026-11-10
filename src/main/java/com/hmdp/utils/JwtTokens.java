package com.hmdp.utils;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
@Component
public class JwtTokens {
    private final byte[] secret;
    public JwtTokens(@Value("${auth.jwt-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
    }
    public String issue(Long userId) {
        return Jwts.builder().setSubject(userId.toString()).setId(UUID.randomUUID().toString())
            .setIssuedAt(new Date()).setExpiration(new Date(System.currentTimeMillis()+86400000L))
            .signWith(Keys.hmacShaKeyFor(secret), SignatureAlgorithm.HS256).compact();
    }
    public Long verify(String token) {
        return Long.valueOf(Jwts.parserBuilder().setSigningKey(Keys.hmacShaKeyFor(secret)).build()
            .parseClaimsJws(token).getBody().getSubject());
    }
    public static String bearer(String value) {
        return value != null && value.startsWith("Bearer ") ? value.substring(7) : value;
    }
}
