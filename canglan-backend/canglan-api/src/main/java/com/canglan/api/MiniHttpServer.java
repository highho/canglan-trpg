package com.canglan.api;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MiniHttpServer — 极简 HTTP/1.1 服务器（ServerSocket 实现，零外部依赖）。
 * 取代 JDK 内置 com.sun.net.httpserver：后者在 Android 上不存在，本类 PC 与 Android 共用。
 * 特性：请求行/Content-Length 解析、path 与 query 自动 URL 解码、最长前缀路由、
 *       每响应统一 CORS 头与 OPTIONS 204、Connection: close（一连接一请求，简单可靠）。
 */
public final class MiniHttpServer {

    /** 请求快照：method/path/query/body（path 已 URL 解码；query 保留原始串，由调用方按需解析）。 */
    public static final class Req {
        public final String method;
        public final String path;
        public final String query;   // 可为 null
        public final byte[] body;

        Req(String method, String path, String query, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.body = body;
        }
    }

    /** 响应载体：handler 填充 status/contentType/body，写回后关闭连接。 */
    public static final class Resp {
        public int status = 200;
        public String contentType = "application/octet-stream";
        public byte[] body = new byte[0];
    }

    @FunctionalInterface
    public interface Handler {
        void handle(Req req, Resp resp) throws Exception;
    }

    private static final class Route {
        final String prefix;
        final Handler handler;

        Route(String prefix, Handler handler) {
            this.prefix = prefix;
            this.handler = handler;
        }
    }

    private final List<Route> routes = new ArrayList<>();
    private ServerSocket socket;
    private ExecutorService pool;
    private volatile boolean running;

    /** 注册路由（最长前缀匹配，与 JDK HttpServer 的 context 语义一致）。 */
    public void route(String prefix, Handler handler) {
        routes.add(new Route(prefix, handler));
    }

    public void start(int port) throws IOException {
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        // 仅监听回环：PC 本机访问与 Android WebView 内嵌均为 127.0.0.1
        socket.bind(new InetSocketAddress("127.0.0.1", port));
        running = true;
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "canglan-mini-http");
            t.setDaemon(true);
            return t;
        });
        Thread accept = new Thread(this::acceptLoop, "canglan-mini-http-accept");
        // 非守护线程：独立运行时阻止 JVM 退出；stop() 关 socket 后本线程自然结束
        accept.start();
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignore) {
            // 关闭失败不影响退出
        }
        if (pool != null) pool.shutdownNow();
    }

    /** 实际监听端口（传 0 启动时由系统分配，冒烟测试用）。 */
    public int port() {
        return socket == null ? -1 : socket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket conn = socket.accept();
                pool.execute(() -> handleConnection(conn));
            } catch (IOException e) {
                if (!running) return;   // stop() 触发的关闭
            }
        }
    }

    private void handleConnection(Socket conn) {
        try (conn) {
            conn.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(conn.getInputStream());
            OutputStream out = new BufferedOutputStream(conn.getOutputStream());

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String target = parts[1];

            // 头部：仅关心 Content-Length
            int contentLength = 0;
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                int ci = header.indexOf(':');
                if (ci > 0 && header.substring(0, ci).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(header.substring(ci + 1).trim());
                    } catch (NumberFormatException ignore) {
                        // 非法长度按 0 处理
                    }
                }
            }
            byte[] body = contentLength > 0 ? readFully(in, contentLength) : new byte[0];

            String path = target;
            String query = null;
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                query = target.substring(qi + 1);
            }

            Resp resp = new Resp();
            if (method.equals("OPTIONS")) {
                resp.status = 204;   // CORS 预检
            } else {
                Handler handler = match(path);
                if (handler == null) {
                    resp.status = 404;
                    resp.contentType = "application/json; charset=utf-8";
                    resp.body = "{\"error\":\"未知路径\"}".getBytes(StandardCharsets.UTF_8);
                } else {
                    try {
                        handler.handle(new Req(method, urlDecode(path), query, body), resp);
                    } catch (Exception e) {
                        resp.status = 500;
                        resp.contentType = "application/json; charset=utf-8";
                        String msg = e.getMessage() == null ? "服务器内部错误" : e.getMessage();
                        resp.body = ("{\"error\":\"" + msg.replace("\"", "'") + "\"}")
                                .getBytes(StandardCharsets.UTF_8);
                    }
                }
            }
            writeResponse(out, resp);
            out.flush();
        } catch (IOException ignore) {
            // 连接级异常直接放弃该请求
        }
    }

    private Handler match(String path) {
        int best = -1;
        Handler found = null;
        for (Route r : routes) {
            if (path.startsWith(r.prefix) && r.prefix.length() > best) {
                best = r.prefix.length();
                found = r.handler;
            }
        }
        return found;
    }

    private static void writeResponse(OutputStream out, Resp resp) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(resp.status).append(' ').append(statusText(resp.status)).append("\r\n");
        head.append("Content-Type: ").append(resp.contentType).append("\r\n");
        head.append("Content-Length: ").append(resp.body.length).append("\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        head.append("Access-Control-Allow-Headers: Content-Type\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(resp.body);
    }

    private static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            default: return "OK";
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(buf, off, length - off);
            if (n < 0) break;
            off += n;
        }
        if (off == length) return buf;
        byte[] trimmed = new byte[off];
        System.arraycopy(buf, 0, trimmed, 0, off);
        return trimmed;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return s;
        }
    }
}
