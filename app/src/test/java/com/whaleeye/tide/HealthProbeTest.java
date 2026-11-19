package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** 用裸 ServerSocket 起一个最小 HTTP 响应，验证探活的 200 / 非 200 分支。 */
public class HealthProbeTest {
    @Test
    public void returnsBodyOn200() throws Exception {
        ServerSocket server = serve("200 OK", "{\"ok\":true,\"db\":\"ok\"}");
        try {
            String base = "http://127.0.0.1:" + server.getLocalPort();
            assertEquals("{\"ok\":true,\"db\":\"ok\"}", TideClient.probeHealthBody(base));
        } finally {
            server.close();
        }
    }

    @Test
    public void dbError503StillReturnsBody() throws Exception {
        ServerSocket server = serve("503 Service Unavailable", "{\"ok\":false,\"db\":\"error\"}");
        try {
            String base = "http://127.0.0.1:" + server.getLocalPort();
            assertEquals("{\"ok\":false,\"db\":\"error\"}", TideClient.probeHealthBody(base));
        } finally {
            server.close();
        }
    }

    @Test
    public void otherStatusFails() throws Exception {
        ServerSocket server = serve("404 Not Found", "");
        try {
            String base = "http://127.0.0.1:" + server.getLocalPort();
            try {
                TideClient.probeHealthBody(base);
                fail("404 应该抛 IOException");
            } catch (IOException expected) {
                // ok
            }
        } finally {
            server.close();
        }
    }

    private static ServerSocket serve(String status, String body) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // 读完请求头即可
                }
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                String head = "HTTP/1.1 " + status + "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + data.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream out = socket.getOutputStream();
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(data);
                out.flush();
            } catch (IOException ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }
}
