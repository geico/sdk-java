package io.temporal.serviceclient;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import javax.annotation.Nullable;
import javax.net.ssl.*;

public class SimpleSslContextBuilder {

  private final @Nullable PKCS pkcs;
  private final @Nullable InputStream keyCertChain;
  private final @Nullable InputStream key;
  private TrustManager trustManager;
  private boolean useInsecureTrustManager;
  private String keyPassword;

  private enum PKCS {
    PKCS_8,
    PKCS_12
  }

  /**
   * @param keyCertChain - an input stream for an X.509 client certificate chain in PEM format.
   * @param key - an input stream for a PKCS#8 client private key in PEM format.
   * @deprecated use {@link #forPKCS8(InputStream, InputStream)} instead
   */
  @Deprecated
  public static SimpleSslContextBuilder newBuilder(InputStream keyCertChain, InputStream key) {
    return forPKCS8(keyCertChain, key);
  }

  /**
   * Explicitly creates a builder without a client private key or certificate chain.
   *
   * <p>{@link #forPKCS8} and {@link #forPKCS12} support null inputs too for easier configuration
   * API
   */
  public static SimpleSslContextBuilder noKeyOrCertChain() {
    return new SimpleSslContextBuilder(null, null, null);
  }

  /**
   * @param keyCertChain - an input stream for an X.509 client certificate chain in PEM format.
   * @param key - an input stream for a PKCS#8 client private key in PEM format.
   */
  public static SimpleSslContextBuilder forPKCS8(
      @Nullable InputStream keyCertChain, @Nullable InputStream key) {
    return new SimpleSslContextBuilder(PKCS.PKCS_8, keyCertChain, key);
  }

  /**
   * @param pfxKeyArchive - an input stream for .pfx or .p12 PKCS12 archive file
   */
  public static SimpleSslContextBuilder forPKCS12(@Nullable InputStream pfxKeyArchive) {
    return new SimpleSslContextBuilder(PKCS.PKCS_12, null, pfxKeyArchive);
  }

  private SimpleSslContextBuilder(
      @Nullable PKCS pkcs, @Nullable InputStream keyCertChain, @Nullable InputStream key) {
    this.pkcs = pkcs;
    this.keyCertChain = keyCertChain;
    this.key = key;
  }

  /**
   * Configures specified {@link SslContextBuilder} from the Builder parameters and for use with
   * Temporal GRPC server. {@link SslContext} built by the configured builder can be used with
   * {@link WorkflowServiceStubsOptions.Builder#setSslContext(SslContext)}
   *
   * <p>If trust manager is set then it will be used to verify server authority, otherwise system
   * default trust manager (or if {@link #useInsecureTrustManager} is set then insecure trust
   * manager) is going to be used.
   *
   * @return {@code sslContextBuilder}
   * @throws SSLException when it was unable to build the context
   */
  public SslContextBuilder configure(SslContextBuilder sslContextBuilder) throws SSLException {
    if (trustManager != null && useInsecureTrustManager)
      throw new IllegalArgumentException(
          "Can not use insecure trust manager if custom trust manager is set.");
    GrpcSslContexts.configure(sslContextBuilder);
    sslContextBuilder.trustManager(
        trustManager != null
            ? trustManager
            : useInsecureTrustManager
                ? InsecureTrustManagerFactory.INSTANCE.getTrustManagers()[0]
                : getDefaultTrustManager());

    if (pkcs != null && (key != null || keyCertChain != null)) {
      switch (pkcs) {
        case PKCS_8:
          // netty by default supports PKCS8
          sslContextBuilder.keyManager(keyCertChain, key, keyPassword);
          break;
        case PKCS_12:
          sslContextBuilder.keyManager(createPKCS12KeyManager());
          break;
        default:
          throw new IllegalArgumentException("PKCS " + pkcs + " is not implemented");
      }
    }
    return sslContextBuilder;
  }

  /**
   * Configures {@link SslContext} from the Builder parameters and for use with Temporal GRPC
   * server.
   *
   * <p>If trust manager is set then it will be used to verify server authority, otherwise system
   * default trust manager (or if {@link #useInsecureTrustManager} is set then insecure trust
   * manager) is going to be used.
   *
   * @return {@link SslContext} that can be used with the {@link
   *     WorkflowServiceStubsOptions.Builder#setSslContext(SslContext)}
   * @throws SSLException when it was unable to build the context
   */
  public SslContext build() throws SSLException {
    return configure(SslContextBuilder.forClient()).build();
  }

  /**
   * @param trustManager - custom trust manager that should be used with the SSLContext for
   *     verifying server CA authority.
   * @return builder instance.
   */
  public SimpleSslContextBuilder setTrustManager(TrustManager trustManager) {
    this.trustManager = trustManager;
    return this;
  }

  /**
   * @param useInsecureTrustManager - if set to true then insecure trust manager is going to be used
   *     instead of the system default one. Note that this makes client vulnerable to man in the
   *     middle attack. Use with caution.
   * @return builder instance.
   */
  public SimpleSslContextBuilder setUseInsecureTrustManager(boolean useInsecureTrustManager) {
    this.useInsecureTrustManager = useInsecureTrustManager;
    return this;
  }

  /**
   * @param keyPassword - the password of the key, or null if it's not password-protected.
   * @return builder instance.
   */
  public SimpleSslContextBuilder setKeyPassword(String keyPassword) {
    this.keyPassword = keyPassword;
    return this;
  }

  private KeyManagerFactory createPKCS12KeyManager() {
    char[] passwordChars = keyPassword != null ? keyPassword.toCharArray() : null;
    try {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(key, passwordChars);
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, passwordChars);
      return kmf;
    } catch (Exception e) {
      throw new IllegalArgumentException("Input stream does not contain a valid PKCS12 key", e);
    }
  }

  /**
   * @return system default trust manager.
   * @throws UnknownDefaultTrustManagerException, which can be caused by {@link
   *     NoSuchAlgorithmException} if {@link TrustManagerFactory#getInstance(String)} doesn't
   *     support default algorithm, {@link KeyStoreException} in case if {@link KeyStore}
   *     initialization failed or if no {@link X509TrustManager} has been found.
   */
  private X509TrustManager getDefaultTrustManager() {
    TrustManagerFactory tmf;
    try {
      tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      // Using null here initialises the TMF with the default trust store.
      tmf.init((KeyStore) null);
    } catch (KeyStoreException | NoSuchAlgorithmException e) {
      throw new UnknownDefaultTrustManagerException(e);
    }

    for (TrustManager tm : tmf.getTrustManagers()) {
      if (tm instanceof X509TrustManager) {
        return (X509TrustManager) tm;
      }
    }
    throw new UnknownDefaultTrustManagerException(
        "Unable to find X509TrustManager in the list of default trust managers.");
  }

  /**
   * Exception that is thrown in case if builder was unable to derive default system trust manager.
   */
  public static final class UnknownDefaultTrustManagerException extends RuntimeException {
    public UnknownDefaultTrustManagerException(Throwable cause) {
      super(cause);
    }

    public UnknownDefaultTrustManagerException(String message) {
      super(message);
    }
  }
}
