/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.tokens;

import static com.google.common.base.Preconditions.checkArgument;

import com.dremio.common.server.WebServerInfoProvider;
import com.dremio.options.OptionManager;
import com.dremio.options.Options;
import com.dremio.options.TypeValidators.BooleanValidator;
import com.dremio.options.TypeValidators.RangeLongValidator;
import com.dremio.service.tokens.jwks.JWKSetManager;
import com.dremio.service.tokens.jwt.ImmutableJWTClaims;
import com.dremio.service.tokens.jwt.JWTClaims;
import com.dremio.service.users.User;
import com.dremio.service.users.UserNotFoundException;
import com.dremio.service.users.UserResolver;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.inject.Provider;

/**
 * This token manager issues, validates and invalidates JWTs.
 *
 * <p>For phase 1 of our migration to using JWTs as access token managers, this token manager will
 * also validate legacy opaque access tokens generated by TokenManagerImpl.
 */
@Options
public class TokenManagerImplV2 implements TokenManager {
  public static final BooleanValidator ENABLE_JWT_ACCESS_TOKENS =
      new BooleanValidator("token.jwt-access-token.enabled", false);
  public static final RangeLongValidator TOKEN_EXPIRATION_TIME_MINUTES =
      new RangeLongValidator(
          "token.jwt-access-token.expiration.minutes",
          15,
          TimeUnit.MINUTES.convert(24, TimeUnit.HOURS),
          60);

  private final Clock clock;
  private final Provider<TokenManager> legacyTokenManagerProvider;
  private final Provider<OptionManager> optionManagerProvider;
  private final Provider<WebServerInfoProvider> webServerInfoProvider;
  private final Provider<UserResolver> userResolverProvider;
  private final Provider<JWKSetManager> jwkSetManagerProvider;

  public TokenManagerImplV2(
      Clock clock,
      Provider<TokenManager> legacyTokenManagerProvider,
      Provider<OptionManager> optionManagerProvider,
      Provider<WebServerInfoProvider> webServerInfoProvider,
      Provider<UserResolver> userResolverProvider,
      Provider<JWKSetManager> jwkSetManagerProvider) {
    this.clock = clock;
    this.legacyTokenManagerProvider = legacyTokenManagerProvider;
    this.optionManagerProvider = optionManagerProvider;
    this.webServerInfoProvider = webServerInfoProvider;
    this.userResolverProvider = userResolverProvider;
    this.jwkSetManagerProvider = jwkSetManagerProvider;
  }

  @Override
  public void start() throws Exception {}

  @Override
  public void close() throws Exception {}

  @Override
  public String newToken() {
    return legacyTokenManagerProvider.get().newToken();
  }

  @Override
  public TokenDetails createToken(String username, String clientAddress) {
    return legacyTokenManagerProvider.get().createToken(username, clientAddress);
  }

  @Override
  public TokenDetails createToken(
      String username, String clientAddress, long expiresAtEpochMs, List<String> scopes) {
    return legacyTokenManagerProvider
        .get()
        .createToken(username, clientAddress, expiresAtEpochMs, scopes);
  }

  @Override
  public TokenDetails createJwt(String username, String clientAddress) {
    return createJwt(username, clientAddress, Long.MAX_VALUE);
  }

  @Override
  public TokenDetails createJwt(String username, String clientAddress, long expiresAtEpochMs) {
    if (!optionManagerProvider.get().getOption(ENABLE_JWT_ACCESS_TOKENS)) {
      throw new UnsupportedOperationException("JWT creation is disabled");
    }

    final Instant now = clock.instant();
    final Date expirationTime =
        new Date(
            Math.min(
                now.plus(
                        Duration.ofMinutes(
                            optionManagerProvider.get().getOption(TOKEN_EXPIRATION_TIME_MINUTES)))
                    .toEpochMilli(),
                expiresAtEpochMs));
    final String token =
        newJWT(
            username, expirationTime, new Date(now.toEpochMilli()), UUID.randomUUID().toString());

    return TokenDetails.of(token, username, expirationTime.getTime());
  }

  private String newJWT(String username, Date expirationTime, Date now, String jti) {
    final User user;
    try {
      user = userResolverProvider.get().getUser(username);
    } catch (UserNotFoundException e) {
      throw new IllegalArgumentException("User does not exist for the given username", e);
    }

    final WebServerInfoProvider webServerInfo = webServerInfoProvider.get();
    final JWTClaims jwt =
        new ImmutableJWTClaims.Builder()
            .setIssuer(webServerInfo.getBaseURL().toString())
            .setSubject(user.getUID().getId())
            .setAudience(webServerInfo.getClusterId())
            .setExpirationTime(expirationTime)
            .setNotBeforeTime(now)
            .setIssueTime(now)
            .setJWTId(jti)
            .build();
    return jwkSetManagerProvider.get().getSigner().sign(jwt);
  }

  @Override
  public TokenDetails createThirdPartyToken(
      String username,
      String clientAddress,
      String clientID,
      List<String> scopes,
      long durationMillis) {
    return legacyTokenManagerProvider
        .get()
        .createThirdPartyToken(username, clientAddress, clientID, scopes, durationMillis);
  }

  @Override
  public TokenDetails createTemporaryToken(
      String username, String path, Map<String, List<String>> queryParams, long durationMillis) {
    return legacyTokenManagerProvider
        .get()
        .createTemporaryToken(username, path, queryParams, durationMillis);
  }

  @Override
  public TokenDetails validateToken(String token) throws IllegalArgumentException {
    checkArgument(token != null, "invalid token");
    final TokenManager legacyTokenManager = legacyTokenManagerProvider.get();

    // Only validate legacy tokens if JWTs are disabled
    if (!optionManagerProvider.get().getOption(ENABLE_JWT_ACCESS_TOKENS)) {
      return legacyTokenManager.validateToken(token);
    }

    try {
      return jwkSetManagerProvider.get().getValidator().validate(token);
    } catch (ParseException | UnsupportedOperationException e) {
      return legacyTokenManager.validateToken(token);
    }
  }

  @Override
  public TokenDetails validateTemporaryToken(
      String token, String path, Map<String, List<String>> queryParams) {
    return legacyTokenManagerProvider.get().validateTemporaryToken(token, path, queryParams);
  }

  @Override
  public void invalidateToken(String token) {
    // Always delegate to legacy token manager if JWTs are disabled
    if (!optionManagerProvider.get().getOption(ENABLE_JWT_ACCESS_TOKENS)) {
      legacyTokenManagerProvider.get().invalidateToken(token);
      return;
    }

    // Ensure the token is not a JWT, since we can't invalidate JWTs yet.
    try {
      jwkSetManagerProvider.get().getValidator().validate(token);
    } catch (ParseException | UnsupportedOperationException ignored) {
      // Expect a ParseException if the given token is not a valid JWT, and therefore may be a valid
      // legacy opaque token that can be invalidated.
      legacyTokenManagerProvider.get().invalidateToken(token);
      return;
    } catch (Exception e) {
      // Any other exception indicates it's a JWT that's failed other checks that make it an invalid
      // JWT.
      throw new IllegalArgumentException("Cannot invalidate given token: token is not valid");
    }

    throw new IllegalArgumentException("Cannot invalidate given token: token is not valid");
  }
}