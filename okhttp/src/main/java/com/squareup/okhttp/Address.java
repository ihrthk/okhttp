/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Util;

import java.net.Proxy;
import java.net.ProxySelector;
import java.util.List;

import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

import static com.squareup.okhttp.internal.Util.equal;

/**
 * A specification for a connection to an origin server. For simple connections,
 * this is the server's hostname and port. If an explicit proxy is requested (or
 * {@linkplain Proxy#NO_PROXY no proxy} is explicitly requested), this also includes
 * that proxy information. For secure connections the address also includes the
 * SSL socket factory and hostname verifier.
 * <p/>
 * <p>HTTP requests that share the same {@code Address} may also share the same
 * {@link Connection}.
 */
public final class Address {
    final Proxy proxy;
    final String uriHost;
    final int uriPort;
    final SocketFactory socketFactory;
    final SSLSocketFactory sslSocketFactory;
    final HostnameVerifier hostnameVerifier;
    final CertificatePinner certificatePinner;
    final Authenticator authenticator;
    final List<Protocol> protocols;
    final List<ConnectionSpec> connectionSpecs;
    final ProxySelector proxySelector;

    public Address(String uriHost, int uriPort, SocketFactory socketFactory,
                   SSLSocketFactory sslSocketFactory, HostnameVerifier hostnameVerifier,
                   CertificatePinner certificatePinner, Authenticator authenticator, Proxy proxy,
                   List<Protocol> protocols, List<ConnectionSpec> connectionSpecs, ProxySelector proxySelector) {
        if (uriHost == null) throw new NullPointerException("uriHost == null");
        if (uriPort <= 0) throw new IllegalArgumentException("uriPort <= 0: " + uriPort);
        if (authenticator == null) throw new IllegalArgumentException("authenticator == null");
        if (protocols == null) throw new IllegalArgumentException("protocols == null");
        if (proxySelector == null) throw new IllegalArgumentException("proxySelector == null");
        this.proxy = proxy;
        this.uriHost = uriHost;
        this.uriPort = uriPort;
        this.socketFactory = socketFactory;
        this.sslSocketFactory = sslSocketFactory;
        this.hostnameVerifier = hostnameVerifier;
        this.certificatePinner = certificatePinner;
        this.authenticator = authenticator;
        this.protocols = Util.immutableList(protocols);
        this.connectionSpecs = Util.immutableList(connectionSpecs);
        this.proxySelector = proxySelector;
    }

    /**
     * Returns the hostname of the origin server.
     */
    public String getUriHost() {
        return uriHost;
    }

    /**
     * Returns the port of the origin server; typically 80 or 443. Unlike
     * may {@code getPort()} accessors, this method never returns -1.
     */
    public int getUriPort() {
        return uriPort;
    }

    /**
     * Returns the socket factory for new connections.
     */
    public SocketFactory getSocketFactory() {
        return socketFactory;
    }

    /**
     * Returns the SSL socket factory, or null if this is not an HTTPS
     * address.
     */
    public SSLSocketFactory getSslSocketFactory() {
        return sslSocketFactory;
    }

    /**
     * Returns the hostname verifier, or null if this is not an HTTPS
     * address.
     */
    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    /**
     * Returns the client's authenticator. This method never returns null.
     */
    public Authenticator getAuthenticator() {
        return authenticator;
    }

    /**
     * Returns the protocols the client supports. This method always returns a
     * non-null list that contains minimally {@link Protocol#HTTP_1_1}.
     */
    public List<Protocol> getProtocols() {
        return protocols;
    }

    public List<ConnectionSpec> getConnectionSpecs() {
        return connectionSpecs;
    }

    /**
     * Returns this address's explicitly-specified HTTP proxy, or null to
     * delegate to the {@linkplain #getProxySelector proxy selector}.
     */
    public Proxy getProxy() {
        return proxy;
    }

    /**
     * Returns this address's proxy selector. Only used if the proxy is null. If none of this
     * selector's proxies are reachable, a direct connection will be attempted.
     */
    public ProxySelector getProxySelector() {
        return proxySelector;
    }

    /**
     * Returns this address's certificate pinner. Only used for secure connections.
     */
    public CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof Address) {
            Address that = (Address) other;
            return equal(this.proxy, that.proxy)
                    && this.uriHost.equals(that.uriHost)
                    && this.uriPort == that.uriPort
                    && equal(this.sslSocketFactory, that.sslSocketFactory)
                    && equal(this.hostnameVerifier, that.hostnameVerifier)
                    && equal(this.certificatePinner, that.certificatePinner)
                    && equal(this.authenticator, that.authenticator)
                    && equal(this.protocols, that.protocols)
                    && equal(this.connectionSpecs, that.connectionSpecs)
                    && equal(this.proxySelector, that.proxySelector);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (proxy != null ? proxy.hashCode() : 0);
        result = 31 * result + uriHost.hashCode();
        result = 31 * result + uriPort;
        result = 31 * result + (sslSocketFactory != null ? sslSocketFactory.hashCode() : 0);
        result = 31 * result + (hostnameVerifier != null ? hostnameVerifier.hashCode() : 0);
        result = 31 * result + (certificatePinner != null ? certificatePinner.hashCode() : 0);
        result = 31 * result + authenticator.hashCode();
        result = 31 * result + protocols.hashCode();
        result = 31 * result + connectionSpecs.hashCode();
        result = 31 * result + proxySelector.hashCode();
        return result;
    }
}
