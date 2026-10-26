// package com.demo.auth;
//
// import com.auth0.jwt.JWT;
// import com.auth0.jwt.algorithms.Algorithm;
// import com.auth0.jwt.exceptions.JWTVerificationException;
// import com.auth0.jwt.interfaces.DecodedJWT;
// import java.util.Date;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.stereotype.Service;
//
// @Service
// public class JwtService {
//     private static final long TOKEN_LIFETIME_MILLIS = 24L * 60 * 60 * 1000;
//     private final Algorithm algorithm;
//
//     public JwtService(@Value("${JWT_SECRET:}") String secret) {
//         if (secret == null || secret.isEmpty()) {
//             throw new IllegalStateException("Thiếu JWT_SECRET trong cấu hình môi trường.");
//         }
//         this.algorithm = Algorithm.HMAC256(secret);
//     }
//
//     public String createToken(long userId, String email) {
//         Date now = new Date();
//         return JWT.create()
//                 .withClaim("id", userId)
//                 .withClaim("email", email)
//                 .withIssuedAt(now)
//                 .withExpiresAt(new Date(now.getTime() + TOKEN_LIFETIME_MILLIS))
//                 .sign(algorithm);
//     }
//
//     public DecodedJWT verify(String token) throws JWTVerificationException {
//         return JWT.require(algorithm).build().verify(token);
//     }
// }
