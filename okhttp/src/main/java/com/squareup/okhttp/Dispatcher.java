/*
 * Copyright (C) 2013 Square, Inc.
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

import com.squareup.okhttp.Call.AsyncCall;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.HttpEngine;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Policy on when async requests are executed.
 * <p/>
 * <p>Each dispatcher uses an {@link ExecutorService} to run calls internally. If you
 * supply your own executor, it should be able to run {@linkplain #getMaxRequests the
 * configured maximum} number of calls concurrently.
 */
public final class Dispatcher {
    private int maxRequests = 64;
    private int maxRequestsPerHost = 5;

    /**
     * Executes calls. Created lazily.
     */
    private ExecutorService executorService;

    /**
     * Ready calls in the order they'll be run.
     */
    private final Deque<AsyncCall> readyCalls = new ArrayDeque<>();

    /**
     * Running calls. Includes canceled calls that haven't finished yet.
     */
    private final Deque<AsyncCall> runningCalls = new ArrayDeque<>();

    /**
     * In-flight synchronous calls. Includes canceled calls that haven't finished yet.
     */
    private final Deque<Call> executedCalls = new ArrayDeque<>();

    public Dispatcher(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public Dispatcher() {
    }

    public synchronized ExecutorService getExecutorService() {
        if (executorService == null) {
            executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(), Util.threadFactory("OkHttp Dispatcher", false));
        }
        return executorService;
    }

    /**
     * Set the maximum number of requests to execute concurrently. Above this
     * requests queue in memory, waiting for the running calls to complete.
     * <p/>
     * <p>If more than {@code maxRequests} requests are in flight when this is
     * invoked, those requests will remain in flight.
     */
    public synchronized void setMaxRequests(int maxRequests) {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequests);
        }
        this.maxRequests = maxRequests;
        promoteCalls();
    }

    public synchronized int getMaxRequests() {
        return maxRequests;
    }

    /**
     * Set the maximum number of requests for each host to execute concurrently.
     * This limits requests by the URL's host name. Note that concurrent requests
     * to a single IP address may still exceed this limit: multiple hostnames may
     * share an IP address or be routed through the same HTTP proxy.
     * <p/>
     * <p>If more than {@code maxRequestsPerHost} requests are in flight when this
     * is invoked, those requests will remain in flight.
     */
    public synchronized void setMaxRequestsPerHost(int maxRequestsPerHost) {
        if (maxRequestsPerHost < 1) {
            throw new IllegalArgumentException("max < 1: " + maxRequestsPerHost);
        }
        this.maxRequestsPerHost = maxRequestsPerHost;
        promoteCalls();
    }

    public synchronized int getMaxRequestsPerHost() {
        return maxRequestsPerHost;
    }

    synchronized void enqueue(AsyncCall call) {
        if (runningCalls.size() < maxRequests && runningCallsForHost(call) < maxRequestsPerHost) {
            runningCalls.add(call);
            getExecutorService().execute(call);
        } else {
            readyCalls.add(call);
        }
    }

    /**
     * Cancel all calls with the tag {@code tag}.
     */
    public synchronized void cancel(Object tag) {
        for (AsyncCall call : readyCalls) {
            if (Util.equal(tag, call.tag())) {
                call.cancel();
            }
        }

        for (AsyncCall call : runningCalls) {
            if (Util.equal(tag, call.tag())) {
                call.get().canceled = true;
                HttpEngine engine = call.get().engine;
                if (engine != null) engine.disconnect();
            }
        }

        for (Call call : executedCalls) {
            if (Util.equal(tag, call.tag())) {
                call.cancel();
            }
        }
    }

    /**
     * Used by {@code AsyncCall#run} to signal completion.
     */
    synchronized void finished(AsyncCall call) {
        if (!runningCalls.remove(call)) throw new AssertionError("AsyncCall wasn't running!");
        promoteCalls();
    }

    private void promoteCalls() {
        if (runningCalls.size() >= maxRequests) return; // Already running max capacity.
        if (readyCalls.isEmpty()) return; // No ready calls to promote.

        for (Iterator<AsyncCall> i = readyCalls.iterator(); i.hasNext(); ) {
            AsyncCall call = i.next();

            if (runningCallsForHost(call) < maxRequestsPerHost) {
                i.remove();
                runningCalls.add(call);
                getExecutorService().execute(call);
            }

            if (runningCalls.size() >= maxRequests) return; // Reached max capacity.
        }
    }

    /**
     * Returns the number of running calls that share a host with {@code call}.
     */
    private int runningCallsForHost(AsyncCall call) {
        int result = 0;
        for (AsyncCall c : runningCalls) {
            if (c.host().equals(call.host())) result++;
        }
        return result;
    }

    /**
     * Used by {@code Call#execute} to signal it is in-flight.
     */
    synchronized void executed(Call call) {
        executedCalls.add(call);
    }

    /**
     * Used by {@code Call#execute} to signal completion.
     */
    synchronized void finished(Call call) {
        if (!executedCalls.remove(call)) throw new AssertionError("Call wasn't in-flight!");
    }

    public synchronized int getRunningCallCount() {
        return runningCalls.size();
    }

    public synchronized int getQueuedCallCount() {
        return readyCalls.size();
    }
}
