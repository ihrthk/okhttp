package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.CacheControl;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.util.Date;

import static com.squareup.okhttp.internal.http.StatusLine.HTTP_PERM_REDIRECT;
import static com.squareup.okhttp.internal.http.StatusLine.HTTP_TEMP_REDIRECT;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_MULT_CHOICE;
import static java.net.HttpURLConnection.HTTP_NOT_AUTHORITATIVE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NOT_IMPLEMENTED;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_REQ_TOO_LONG;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Given a request and cached response, this figures out whether to use the
 * network, the cache, or both.
 * <p/>
 * <p>Selecting a cache strategy may add conditions to the request (like the
 * "If-Modified-Since" header for conditional GETs) or warnings to the cached
 * response (if the cached data is potentially stale).
 */
public final class CacheStrategy {
    /**
     * The request to send on the network, or null if this call doesn't use the network.
     */
    public final Request networkRequest;

    /**
     * The cached response to return or validate; or null if this call doesn't use a cache.
     */
    public final Response cacheResponse;

    private CacheStrategy(Request networkRequest, Response cacheResponse) {
        this.networkRequest = networkRequest;
        this.cacheResponse = cacheResponse;
    }

    /**
     * Returns true if {@code response} can be stored to later serve another
     * request.
     */
    public static boolean isCacheable(Response response, Request request) {
        // Always go to network for uncacheable response codes (RFC 7231 section 6.1),
        // This implementation doesn't support caching partial content.
        switch (response.code()) {
            case HTTP_OK:
            case HTTP_NOT_AUTHORITATIVE:
            case HTTP_NO_CONTENT:
            case HTTP_MULT_CHOICE:
            case HTTP_MOVED_PERM:
            case HTTP_NOT_FOUND:
            case HTTP_BAD_METHOD:
            case HTTP_GONE:
            case HTTP_REQ_TOO_LONG:
            case HTTP_NOT_IMPLEMENTED:
            case HTTP_PERM_REDIRECT:
                // These codes can be cached unless headers forbid it.
                break;

            case HTTP_MOVED_TEMP:
            case HTTP_TEMP_REDIRECT:
                // These codes can only be cached with the right response headers.
                // http://tools.ietf.org/html/rfc7234#section-3
                // s-maxage is not checked because OkHttp is a private cache that should ignore s-maxage.
                if (response.header("Expires") != null
                        || response.cacheControl().maxAgeSeconds() != -1
                        || response.cacheControl().isPublic()
                        || response.cacheControl().isPrivate()) {
                    break;
                }
                // Fall-through.

            default:
                // All other codes cannot be cached.
                return false;
        }

        // A 'no-store' directive on request or response prevents the response from being cached.
        return !response.cacheControl().noStore() && !request.cacheControl().noStore();
    }

    public static class Factory {
        final long nowMillis;
        final Request request;
        final Response cacheResponse;

        /**
         * The server's time when the cached response was served, if known.
         */
        private Date servedDate;
        private String servedDateString;

        /**
         * The last modified date of the cached response, if known.
         */
        private Date lastModified;
        private String lastModifiedString;

        /**
         * The expiration date of the cached response, if known. If both this field
         * and the max age are set, the max age is preferred.
         */
        private Date expires;

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached
         * HTTP request was first initiated.
         */
        private long sentRequestMillis;

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached
         * HTTP response was first received.
         */
        private long receivedResponseMillis;

        /**
         * Etag of the cached response.
         */
        private String etag;

        /**
         * Age of the cached response.
         */
        private int ageSeconds = -1;

        public Factory(long nowMillis, Request request, Response cacheResponse) {
            this.nowMillis = nowMillis;
            this.request = request;
            this.cacheResponse = cacheResponse;

            if (cacheResponse != null) {
                Headers headers = cacheResponse.headers();
                for (int i = 0, size = headers.size(); i < size; i++) {
                    String fieldName = headers.name(i);
                    String value = headers.value(i);
                    if ("Date".equalsIgnoreCase(fieldName)) {
                        servedDate = HttpDate.parse(value);
                        servedDateString = value;
                    } else if ("Expires".equalsIgnoreCase(fieldName)) {
                        expires = HttpDate.parse(value);
                    } else if ("Last-Modified".equalsIgnoreCase(fieldName)) {
                        lastModified = HttpDate.parse(value);
                        lastModifiedString = value;
                    } else if ("ETag".equalsIgnoreCase(fieldName)) {
                        etag = value;
                    } else if ("Age".equalsIgnoreCase(fieldName)) {
                        ageSeconds = HeaderParser.parseSeconds(value, -1);
                    } else if (OkHeaders.SENT_MILLIS.equalsIgnoreCase(fieldName)) {
                        sentRequestMillis = Long.parseLong(value);
                    } else if (OkHeaders.RECEIVED_MILLIS.equalsIgnoreCase(fieldName)) {
                        receivedResponseMillis = Long.parseLong(value);
                    }
                }
            }
        }

        /**
         * Returns a strategy to satisfy {@code request} using the a cached response
         * {@code response}.
         */
        public CacheStrategy get() {
            CacheStrategy candidate = getCandidate();

            if (candidate.networkRequest != null && request.cacheControl().onlyIfCached()) {
                // We're forbidden from using the network and the cache is insufficient.
                return new CacheStrategy(null, null);
            }

            return candidate;
        }

        /**
         * Returns a strategy to use assuming the request can use the network.
         */
        private CacheStrategy getCandidate() {
            // No cached response.
            if (cacheResponse == null) {
                return new CacheStrategy(request, null);
            }

            // Drop the cached response if it's missing a required handshake.
            if (request.isHttps() && cacheResponse.handshake() == null) {
                return new CacheStrategy(request, null);
            }

            // If this response shouldn't have been stored, it should never be used
            // as a response source. This check should be redundant as long as the
            // persistence store is well-behaved and the rules are constant.
            if (!isCacheable(cacheResponse, request)) {
                return new CacheStrategy(request, null);
            }

            CacheControl requestCaching = request.cacheControl();
            if (requestCaching.noCache() || hasConditions(request)) {
                return new CacheStrategy(request, null);
            }

            long ageMillis = cacheResponseAge();
            long freshMillis = computeFreshnessLifetime();

            if (requestCaching.maxAgeSeconds() != -1) {
                freshMillis = Math.min(freshMillis, SECONDS.toMillis(requestCaching.maxAgeSeconds()));
            }

            long minFreshMillis = 0;
            if (requestCaching.minFreshSeconds() != -1) {
                minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds());
            }

            long maxStaleMillis = 0;
            CacheControl responseCaching = cacheResponse.cacheControl();
            if (!responseCaching.mustRevalidate() && requestCaching.maxStaleSeconds() != -1) {
                maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds());
            }

            if (!responseCaching.noCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
                Response.Builder builder = cacheResponse.newBuilder();
                if (ageMillis + minFreshMillis >= freshMillis) {
                    builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"");
                }
                long oneDayMillis = 24 * 60 * 60 * 1000L;
                if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
                    builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
                }
                return new CacheStrategy(null, builder.build());
            }

            Request.Builder conditionalRequestBuilder = request.newBuilder();

            if (etag != null) {
                conditionalRequestBuilder.header("If-None-Match", etag);
            } else if (lastModified != null) {
                conditionalRequestBuilder.header("If-Modified-Since", lastModifiedString);
            } else if (servedDate != null) {
                conditionalRequestBuilder.header("If-Modified-Since", servedDateString);
            }

            Request conditionalRequest = conditionalRequestBuilder.build();
            return hasConditions(conditionalRequest)
                    ? new CacheStrategy(conditionalRequest, cacheResponse)
                    : new CacheStrategy(conditionalRequest, null);
        }

        /**
         * Returns the number of milliseconds that the response was fresh for,
         * starting from the served date.
         */
        private long computeFreshnessLifetime() {
            CacheControl responseCaching = cacheResponse.cacheControl();
            if (responseCaching.maxAgeSeconds() != -1) {
                return SECONDS.toMillis(responseCaching.maxAgeSeconds());
            } else if (expires != null) {
                long servedMillis = servedDate != null
                        ? servedDate.getTime()
                        : receivedResponseMillis;
                long delta = expires.getTime() - servedMillis;
                return delta > 0 ? delta : 0;
            } else if (lastModified != null
                    && cacheResponse.request().url().getQuery() == null) {
                // As recommended by the HTTP RFC and implemented in Firefox, the
                // max age of a document should be defaulted to 10% of the
                // document's age at the time it was served. Default expiration
                // dates aren't used for URIs containing a query.
                long servedMillis = servedDate != null
                        ? servedDate.getTime()
                        : sentRequestMillis;
                long delta = servedMillis - lastModified.getTime();
                return delta > 0 ? (delta / 10) : 0;
            }
            return 0;
        }

        /**
         * Returns the current age of the response, in milliseconds. The calculation
         * is specified by RFC 2616, 13.2.3 Age Calculations.
         */
        private long cacheResponseAge() {
            long apparentReceivedAge = servedDate != null
                    ? Math.max(0, receivedResponseMillis - servedDate.getTime())
                    : 0;
            long receivedAge = ageSeconds != -1
                    ? Math.max(apparentReceivedAge, SECONDS.toMillis(ageSeconds))
                    : apparentReceivedAge;
            long responseDuration = receivedResponseMillis - sentRequestMillis;
            long residentDuration = nowMillis - receivedResponseMillis;
            return receivedAge + responseDuration + residentDuration;
        }

        /**
         * Returns true if computeFreshnessLifetime used a heuristic. If we used a
         * heuristic to serve a cached response older than 24 hours, we are required
         * to attach a warning.
         */
        private boolean isFreshnessLifetimeHeuristic() {
            return cacheResponse.cacheControl().maxAgeSeconds() == -1 && expires == null;
        }

        /**
         * Returns true if the request contains conditions that save the server from
         * sending a response that the client has locally. When a request is enqueued
         * with its own conditions, the built-in response cache won't be used.
         */
        private static boolean hasConditions(Request request) {
            return request.header("If-Modified-Since") != null || request.header("If-None-Match") != null;
        }
    }
}
