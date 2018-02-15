/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.http.HttpClient.Version;
import jdk.internal.net.http.common.HttpHeadersImpl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Defines an adaptation layers so that a test server handlers and filters
 * can be implemented independently of the underlying server version.
 * <p>
 * For instance:
 * <pre>{@code
 *
 *  URI http1URI, http2URI;
 *
 *  InetSocketAddress sa = new InetSocketAddress("localhost", 0);
 *  HttpTestServer server1 = HttpTestServer.of(HttpServer.create(sa, 0));
 *  HttpTestContext context = server.addHandler(new HttpTestEchoHandler(), "/http1/echo");
 *  http2URI = "http://127.0.0.1:" + server1.getAddress().getPort() + "/http1/echo";
 *
 *  Http2TestServer http2TestServer = new Http2TestServer("127.0.0.1", false, 0);
 *  HttpTestServer server2 = HttpTestServer.of(http2TestServer);
 *  server2.addHandler(new HttpTestEchoHandler(), "/http2/echo");
 *  http1URI = "http://127.0.0.1:" + server2.getAddress().getPort() + "/http2/echo";
 *
 *  }</pre>
 */
public interface HttpServerAdapters {

    static void uncheckedWrite(ByteArrayOutputStream baos, byte[] ba) {
        try {
            baos.write(ba);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void printBytes(PrintStream out, String prefix, byte[] bytes) {
        int padding = 4 + 4 - (bytes.length % 4);
        padding = padding > 4 ? padding - 4 : 4;
        byte[] bigbytes = new byte[bytes.length + padding];
        System.arraycopy(bytes, 0, bigbytes, padding, bytes.length);
        out.println(prefix + bytes.length + " "
                    + new BigInteger(bigbytes).toString(16));
    }

    /**
     * A version agnostic adapter class for HTTP Headers.
     */
    public static abstract class HttpTestHeaders {
        public abstract Optional<String> firstValue(String name);
        public abstract void addHeader(String name, String value);
        public abstract Set<String> keySet();
        public abstract Set<Map.Entry<String, List<String>>> entrySet();
        public abstract List<String> get(String name);
        public abstract boolean containsKey(String name);

        public static HttpTestHeaders of(Headers headers) {
            return new Http1TestHeaders(headers);
        }
        public static HttpTestHeaders of(HttpHeadersImpl headers) {
            return new Http2TestHeaders(headers);
        }

        private final static class Http1TestHeaders extends HttpTestHeaders {
            private final Headers headers;
            Http1TestHeaders(Headers h) { this.headers = h; }
            @Override
            public Optional<String> firstValue(String name) {
                if (headers.containsKey(name)) {
                    return Optional.ofNullable(headers.getFirst(name));
                }
                return Optional.empty();
            }
            @Override
            public void addHeader(String name, String value) {
                headers.add(name, value);
            }

            @Override
            public Set<String> keySet() { return headers.keySet(); }
            @Override
            public Set<Map.Entry<String, List<String>>> entrySet() {
                return headers.entrySet();
            }
            @Override
            public List<String> get(String name) {
                return headers.get(name);
            }
            @Override
            public boolean containsKey(String name) {
                return headers.containsKey(name);
            }
        }
        private final static class Http2TestHeaders extends HttpTestHeaders {
            private final HttpHeadersImpl headers;
            Http2TestHeaders(HttpHeadersImpl h) { this.headers = h; }
            @Override
            public Optional<String> firstValue(String name) {
                return headers.firstValue(name);
            }
            @Override
            public void addHeader(String name, String value) {
                headers.addHeader(name, value);
            }
            public Set<String> keySet() { return headers.map().keySet(); }
            @Override
            public Set<Map.Entry<String, List<String>>> entrySet() {
                return headers.map().entrySet();
            }
            @Override
            public List<String> get(String name) {
                return headers.allValues(name);
            }
            @Override
            public boolean containsKey(String name) {
                return headers.firstValue(name).isPresent();
            }
        }
    }

    /**
     * A version agnostic adapter class for HTTP Server Exchange.
     */
    public static abstract class HttpTestExchange {
        public abstract Version getServerVersion();
        public abstract Version getExchangeVersion();
        public abstract InputStream   getRequestBody();
        public abstract OutputStream  getResponseBody();
        public abstract HttpTestHeaders getRequestHeaders();
        public abstract HttpTestHeaders getResponseHeaders();
        public abstract void sendResponseHeaders(int code, int contentLength) throws IOException;
        public abstract URI getRequestURI();
        public abstract String getRequestMethod();
        public abstract void close();

        public static HttpTestExchange of(HttpExchange exchange) {
            return new Http1TestExchange(exchange);
        }
        public static HttpTestExchange of(Http2TestExchange exchange) {
            return new Http2TestExchangeImpl(exchange);
        }

        abstract void doFilter(Filter.Chain chain) throws IOException;

        // implementations...
        private static final class Http1TestExchange extends HttpTestExchange {
            private final HttpExchange exchange;
            Http1TestExchange(HttpExchange exch) {
                this.exchange = exch;
            }
            @Override
            public Version getServerVersion() { return Version.HTTP_1_1; }
            @Override
            public Version getExchangeVersion() { return Version.HTTP_1_1; }
            @Override
            public InputStream getRequestBody() {
                return exchange.getRequestBody();
            }
            @Override
            public OutputStream getResponseBody() {
                return exchange.getResponseBody();
            }
            @Override
            public HttpTestHeaders getRequestHeaders() {
                return HttpTestHeaders.of(exchange.getRequestHeaders());
            }
            @Override
            public HttpTestHeaders getResponseHeaders() {
                return HttpTestHeaders.of(exchange.getResponseHeaders());
            }
            @Override
            public void sendResponseHeaders(int code, int contentLength) throws IOException {
                if (contentLength == 0) contentLength = -1;
                else if (contentLength < 0) contentLength = 0;
                exchange.sendResponseHeaders(code, contentLength);
            }
            @Override
            void doFilter(Filter.Chain chain) throws IOException {
                chain.doFilter(exchange);
            }
            @Override
            public void close() { exchange.close(); }
            @Override
            public URI getRequestURI() { return exchange.getRequestURI(); }
            @Override
            public String getRequestMethod() { return exchange.getRequestMethod(); }
            @Override
            public String toString() {
                return this.getClass().getSimpleName() + ": " + exchange.toString();
            }
        }
        private static final class Http2TestExchangeImpl extends HttpTestExchange {
            private final Http2TestExchange exchange;
            Http2TestExchangeImpl(Http2TestExchange exch) {
                this.exchange = exch;
            }
            @Override
            public Version getServerVersion() { return Version.HTTP_2; }
            @Override
            public Version getExchangeVersion() { return Version.HTTP_2; }
            @Override
            public InputStream getRequestBody() {
                return exchange.getRequestBody();
            }
            @Override
            public OutputStream getResponseBody() {
                return exchange.getResponseBody();
            }
            @Override
            public HttpTestHeaders getRequestHeaders() {
                return HttpTestHeaders.of(exchange.getRequestHeaders());
            }
            @Override
            public HttpTestHeaders getResponseHeaders() {
                return HttpTestHeaders.of(exchange.getResponseHeaders());
            }
            @Override
            public void sendResponseHeaders(int code, int contentLength) throws IOException {
                if (contentLength == 0) contentLength = -1;
                else if (contentLength < 0) contentLength = 0;
                exchange.sendResponseHeaders(code, contentLength);
            }
            void doFilter(Filter.Chain filter) throws IOException {
                throw new IOException("cannot use HTTP/1.1 filter with HTTP/2 server");
            }
            @Override
            public void close() { exchange.close();}
            @Override
            public URI getRequestURI() { return exchange.getRequestURI(); }
            @Override
            public String getRequestMethod() { return exchange.getRequestMethod(); }
            @Override
            public String toString() {
                return this.getClass().getSimpleName() + ": " + exchange.toString();
            }
        }

    }


    /**
     * A version agnostic adapter class for HTTP Server Handlers.
     */
    public interface HttpTestHandler {
        void handle(HttpTestExchange t) throws IOException;

        default HttpHandler toHttpHandler() {
            return (t) -> handle(HttpTestExchange.of(t));
        }
        default Http2Handler toHttp2Handler() {
            return (t) -> handle(HttpTestExchange.of(t));
        }
    }

    public static class HttpTestEchoHandler implements HttpTestHandler {
        @Override
        public void handle(HttpTestExchange t) throws IOException {
            try (InputStream is = t.getRequestBody();
                 OutputStream os = t.getResponseBody()) {
                byte[] bytes = is.readAllBytes();
                printBytes(System.out,"Echo server got "
                        + t.getExchangeVersion() + " bytes: ", bytes);
                if (t.getRequestHeaders().firstValue("Content-type").isPresent()) {
                    t.getResponseHeaders().addHeader("Content-type",
                            t.getRequestHeaders().firstValue("Content-type").get());
                }
                t.sendResponseHeaders(200, bytes.length);
                os.write(bytes);
            }
        }
    }

    /**
     * A version agnostic adapter class for HTTP Server Filter Chains.
     */
    public abstract class HttpChain {

        public abstract void doFilter(HttpTestExchange exchange) throws IOException;
        public static HttpChain of(Filter.Chain chain) {
            return new Http1Chain(chain);
        }

        public static HttpChain of(List<HttpTestFilter> filters, HttpTestHandler handler) {
            return new Http2Chain(filters, handler);
        }

        private static class Http1Chain extends HttpChain {
            final Filter.Chain chain;
            Http1Chain(Filter.Chain chain) {
                this.chain = chain;
            }
            @Override
            public void doFilter(HttpTestExchange exchange) throws IOException{
                exchange.doFilter(chain);
            }
        }

        private static class Http2Chain extends HttpChain {
            ListIterator<HttpTestFilter> iter;
            HttpTestHandler handler;
            Http2Chain(List<HttpTestFilter> filters, HttpTestHandler handler) {
                this.iter = filters.listIterator();
                this.handler = handler;
            }
            @Override
            public void doFilter(HttpTestExchange exchange) throws IOException {
                if (iter.hasNext()) {
                    iter.next().doFilter(exchange, this);
                } else {
                    handler.handle(exchange);
                }
            }
        }

    }

    /**
     * A version agnostic adapter class for HTTP Server Filters.
     */
    public abstract class HttpTestFilter {

        public abstract String description();

        public abstract void doFilter(HttpTestExchange exchange, HttpChain chain) throws IOException;

        public Filter toFilter() {
            return new Filter() {
                @Override
                public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                    HttpTestFilter.this.doFilter(HttpTestExchange.of(exchange), HttpChain.of(chain));
                }
                @Override
                public String description() {
                    return HttpTestFilter.this.description();
                }
            };
        }
    }

    /**
     * A version agnostic adapter class for HTTP Server Context.
     */
    public static abstract class HttpTestContext {
        public abstract String getPath();
        public abstract void addFilter(HttpTestFilter filter);
        public abstract Version getVersion();

        // will throw UOE if the server is HTTP/2
        public abstract void setAuthenticator(com.sun.net.httpserver.Authenticator authenticator);
    }

    /**
     * A version agnostic adapter class for HTTP Servers.
     */
    public static abstract class HttpTestServer {
        public abstract void start();
        public abstract void stop();
        public abstract HttpTestContext addHandler(HttpTestHandler handler, String root);
        public abstract InetSocketAddress getAddress();
        public abstract Version getVersion();

        public static HttpTestServer of(HttpServer server) {
            return new Http1TestServer(server);
        }

        public static HttpTestServer of(Http2TestServer server) {
            return new Http2TestServerImpl(server);
        }

        private static class Http1TestServer extends  HttpTestServer {
            private final HttpServer impl;
            Http1TestServer(HttpServer server) {
                this.impl = server;
            }
            @Override
            public void start() { impl.start(); }
            @Override
            public void stop() { impl.stop(0); }
            @Override
            public HttpTestContext addHandler(HttpTestHandler handler, String path) {
                return new Http1TestContext(impl.createContext(path, handler.toHttpHandler()));
            }
            @Override
            public InetSocketAddress getAddress() {
                return new InetSocketAddress("127.0.0.1",
                        impl.getAddress().getPort());
            }
            public Version getVersion() { return Version.HTTP_1_1; }
        }

        private static class Http1TestContext extends HttpTestContext {
            private final HttpContext context;
            Http1TestContext(HttpContext ctxt) {
                this.context = ctxt;
            }
            @Override public String getPath() {
                return context.getPath();
            }
            @Override
            public void addFilter(HttpTestFilter filter) {
                context.getFilters().add(filter.toFilter());
            }
            @Override
            public void setAuthenticator(com.sun.net.httpserver.Authenticator authenticator) {
                context.setAuthenticator(authenticator);
            }
            @Override public Version getVersion() { return Version.HTTP_1_1; }
        }

        private static class Http2TestServerImpl extends  HttpTestServer {
            private final Http2TestServer impl;
            Http2TestServerImpl(Http2TestServer server) {
                this.impl = server;
            }
            @Override
            public void start() {
                System.out.println("Http2TestServerImpl: start");
                impl.start();
            }
            @Override
            public void stop() {
                System.out.println("Http2TestServerImpl: stop");
                impl.stop();
            }
            @Override
            public HttpTestContext addHandler(HttpTestHandler handler, String path) {
                System.out.println("Http2TestServerImpl::addHandler " + handler + ", " + path);
                Http2TestContext context = new Http2TestContext(handler, path);
                impl.addHandler(context.toHttp2Handler(), path);
                return context;
            }
            @Override
            public InetSocketAddress getAddress() {
                return new InetSocketAddress("127.0.0.1",
                        impl.getAddress().getPort());
            }
            public Version getVersion() { return Version.HTTP_2; }
        }

        private static class Http2TestContext
                extends HttpTestContext implements HttpTestHandler {
            private final HttpTestHandler handler;
            private final String path;
            private final List<HttpTestFilter> filters = new CopyOnWriteArrayList<>();
            Http2TestContext(HttpTestHandler hdl, String path) {
                this.handler = hdl;
                this.path = path;
            }
            @Override
            public String getPath() { return path; }
            @Override
            public void addFilter(HttpTestFilter filter) {
                System.out.println("Http2TestContext::addFilter " + filter.description());
                filters.add(filter);
            }
            @Override
            public void handle(HttpTestExchange exchange) throws IOException {
                System.out.println("Http2TestContext::handle " + exchange);
                HttpChain.of(filters, handler).doFilter(exchange);
            }
            @Override
            public void setAuthenticator(com.sun.net.httpserver.Authenticator authenticator) {
                throw new UnsupportedOperationException("Can't set HTTP/1.1 authenticator on HTTP/2 context");
            }
            @Override public Version getVersion() { return Version.HTTP_2; }
        }
    }

}
