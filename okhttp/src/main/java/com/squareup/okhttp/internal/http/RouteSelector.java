/*
 * Copyright (C) 2012 Square, Inc.
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
package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Address;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Route;
import com.squareup.okhttp.internal.Internal;
import com.squareup.okhttp.internal.Network;
import com.squareup.okhttp.internal.RouteDatabase;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import static com.squareup.okhttp.internal.Util.getEffectivePort;

/**
 * Selects routes to connect to an origin server. Each connection requires a
 * choice of proxy server, IP address, and TLS mode. Connections may also be
 * recycled.
 */
public final class RouteSelector {
    private final Address address;
    private final URI uri;
    private final Network network;
    private final OkHttpClient client;
    private final RouteDatabase routeDatabase;

    /* The most recently attempted route. */
    private Proxy lastProxy;
    private InetSocketAddress lastInetSocketAddress;

    /* State for negotiating the next proxy to use. */
    private List<Proxy> proxies = Collections.emptyList();
    private int nextProxyIndex;

    /* State for negotiating the next socket address to use. */
    private List<InetSocketAddress> inetSocketAddresses = Collections.emptyList();
    private int nextInetSocketAddressIndex;

    /* State for negotiating failed routes */
    private final List<Route> postponedRoutes = new ArrayList<>();

    private RouteSelector(Address address, URI uri, OkHttpClient client) {
        this.address = address;
        this.uri = uri;
        this.client = client;
        this.routeDatabase = Internal.instance.routeDatabase(client);
        this.network = Internal.instance.network(client);

        resetNextProxy(uri, address.getProxy());
    }

    public static RouteSelector get(Address address, Request request, OkHttpClient client)
            throws IOException {
        return new RouteSelector(address, request.uri(), client);
    }

    /**
     * Returns true if there's another route to attempt. Every address has at
     * least one route.
     */
    public boolean hasNext() {
        return hasNextInetSocketAddress()
                || hasNextProxy()
                || hasNextPostponed();
    }

    public Route next() throws IOException {
        // Compute the next route to attempt.
        if (!hasNextInetSocketAddress()) {
            if (!hasNextProxy()) {
                if (!hasNextPostponed()) {
                    throw new NoSuchElementException();
                }
                return nextPostponed();
            }
            lastProxy = nextProxy();
        }
        lastInetSocketAddress = nextInetSocketAddress();

        Route route = new Route(address, lastProxy, lastInetSocketAddress);
        if (routeDatabase.shouldPostpone(route)) {
            postponedRoutes.add(route);
            // We will only recurse in order to skip previously failed routes. They will be tried last.
            return next();
        }

        return route;
    }

    /**
     * Clients should invoke this method when they encounter a connectivity
     * failure on a connection returned by this route selector.
     */
    public void connectFailed(Route failedRoute, IOException failure) {
        if (failedRoute.getProxy().type() != Proxy.Type.DIRECT && address.getProxySelector() != null) {
            // Tell the proxy selector when we fail to connect on a fresh connection.
            address.getProxySelector().connectFailed(uri, failedRoute.getProxy().address(), failure);
        }

        routeDatabase.failed(failedRoute);
    }

    /**
     * Prepares the proxy servers to try.
     */
    private void resetNextProxy(URI uri, Proxy proxy) {
        if (proxy != null) {
            // If the user specifies a proxy, try that and only that.
            proxies = Collections.singletonList(proxy);
        } else {
            // Try each of the ProxySelector choices until one connection succeeds. If none succeed
            // then we'll try a direct connection below.
            proxies = new ArrayList<>();
            List<Proxy> selectedProxies = client.getProxySelector().select(uri);
            if (selectedProxies != null) proxies.addAll(selectedProxies);
            // Finally try a direct connection. We only try it once!
            proxies.removeAll(Collections.singleton(Proxy.NO_PROXY));
            proxies.add(Proxy.NO_PROXY);
        }
        nextProxyIndex = 0;
    }

    /**
     * Returns true if there's another proxy to try.
     */
    private boolean hasNextProxy() {
        return nextProxyIndex < proxies.size();
    }

    /**
     * Returns the next proxy to try. May be PROXY.NO_PROXY but never null.
     */
    private Proxy nextProxy() throws IOException {
        if (!hasNextProxy()) {
            throw new SocketException("No route to " + address.getUriHost()
                    + "; exhausted proxy configurations: " + proxies);
        }
        Proxy result = proxies.get(nextProxyIndex++);
        resetNextInetSocketAddress(result);
        return result;
    }

    /**
     * Prepares the socket addresses to attempt for the current proxy or host.
     */
    private void resetNextInetSocketAddress(Proxy proxy) throws IOException {
        // Clear the addresses. Necessary if getAllByName() below throws!
        inetSocketAddresses = new ArrayList<>();

        String socketHost;
        int socketPort;
        if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
            socketHost = address.getUriHost();
            socketPort = getEffectivePort(uri);
        } else {
            SocketAddress proxyAddress = proxy.address();
            if (!(proxyAddress instanceof InetSocketAddress)) {
                throw new IllegalArgumentException(
                        "Proxy.address() is not an " + "InetSocketAddress: " + proxyAddress.getClass());
            }
            InetSocketAddress proxySocketAddress = (InetSocketAddress) proxyAddress;
            socketHost = getHostString(proxySocketAddress);
            socketPort = proxySocketAddress.getPort();
        }

        if (socketPort < 1 || socketPort > 65535) {
            throw new SocketException("No route to " + socketHost + ":" + socketPort
                    + "; port is out of range");
        }

        // Try each address for best behavior in mixed IPv4/IPv6 environments.
        for (InetAddress inetAddress : network.resolveInetAddresses(socketHost)) {
            inetSocketAddresses.add(new InetSocketAddress(inetAddress, socketPort));
        }

        nextInetSocketAddressIndex = 0;
    }

    /**
     * Obtain a "host" from an {@link InetSocketAddress}. This returns a string containing either an
     * actual host name or a numeric IP address.
     */
    // Visible for testing
    static String getHostString(InetSocketAddress socketAddress) {
        InetAddress address = socketAddress.getAddress();
        if (address == null) {
            // The InetSocketAddress was specified with a string (either a numeric IP or a host name). If
            // it is a name, all IPs for that name should be tried. If it is an IP address, only that IP
            // address should be tried.
            return socketAddress.getHostName();
        }
        // The InetSocketAddress has a specific address: we should only try that address. Therefore we
        // return the address and ignore any host name that may be available.
        return address.getHostAddress();
    }

    /**
     * Returns true if there's another socket address to try.
     */
    private boolean hasNextInetSocketAddress() {
        return nextInetSocketAddressIndex < inetSocketAddresses.size();
    }

    /**
     * Returns the next socket address to try.
     */
    private InetSocketAddress nextInetSocketAddress() throws IOException {
        if (!hasNextInetSocketAddress()) {
            throw new SocketException("No route to " + address.getUriHost()
                    + "; exhausted inet socket addresses: " + inetSocketAddresses);
        }
        return inetSocketAddresses.get(nextInetSocketAddressIndex++);
    }

    /**
     * Returns true if there is another postponed route to try.
     */
    private boolean hasNextPostponed() {
        return !postponedRoutes.isEmpty();
    }

    /**
     * Returns the next postponed route to try.
     */
    private Route nextPostponed() {
        return postponedRoutes.remove(0);
    }
}
