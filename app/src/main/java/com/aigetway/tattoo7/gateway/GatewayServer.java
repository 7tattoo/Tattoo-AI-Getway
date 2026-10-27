package com.aigetway.tattoo7.gateway;

import com.aigetway.tattoo7.core.App;
import com.aigetway.tattoo7.core.LogSink;
import com.aigetway.tattoo7.core.Settings;



import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 本地 HTTP 网关：ServerSocket + 线程池；默认绑 127.0.0.1，可选绑 0.0.0.0（局域网访问）。 */
public class GatewayServer {

    
    private final LogSink log;
    private final Settings settings;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private volatile int port = -1;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    public GatewayServer() {
        
        this.log = App.get().logSink();
        this.settings = App.get().settings();
    }

    public boolean isRunning() { return running.get(); }
    public int port() { return port; }
    public int requestCount() { return requestCount.get(); }

    public void start(int port) throws IOException {
        if (running.get()) return;
        boolean lan = settings.lanEnabled();
        String bindAddr = lan ? "0.0.0.0" : "127.0.0.1";
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddr, port), 128);
        this.port = port;
        running.set(true);
        log.log("GATEWAY", "监听 " + bindAddr + ":" + port + (lan ? "（局域网可访问）" : ""));

        acceptThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket s = serverSocket.accept();
                    requestCount.incrementAndGet();
                    pool.execute(() -> {
                        try {
                            new Router().handle(s);
                        } catch (IOException e) {
                            // client closed
                        } finally {
                            try { s.close(); } catch (IOException ignored) {}
                        }
                    });
                } catch (IOException e) {
                    if (running.get()) log.log("GATEWAY", "accept error: " + e.getMessage());
                }
            }
        }, "gateway-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        try { if (acceptThread != null) acceptThread.interrupt(); } catch (Exception ignored) {}
        pool.shutdownNow();
        log.log("GATEWAY", "已停止");
        port = -1;
    }
}