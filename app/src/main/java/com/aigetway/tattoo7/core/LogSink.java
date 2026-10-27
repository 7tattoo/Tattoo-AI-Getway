package com.aigetway.tattoo7.core;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;

public class LogSink {

    private static final int MAX_LINES = 500;
    /** 落盘上限：超过就滚一个 .1 备份，避免长期运行把空间写满。 */
    private static final long MAX_FILE = 512 * 1024L;
    private final Deque<String> buffer = new ArrayDeque<>();
    private File file;

    /**
     * 日志同时落盘，供排障时直接读文件（无需截图界面）。
     *
     * <p>路径由 App 传入（通常是
     * {@code /sdcard/Android/data/com.aigetway.tattoo7/files/logs/app.log}）：
     * 网络层的原始状态码与上游报文都记在这里，是「刷新失败/模型 402」这类
     * 「界面只显示一句话」问题的主要证据来源。
     */
    public void attachFile(File f) {
        this.file = f;
    }

    public synchronized void log(String tag, String msg) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date())
                + "] " + tag + ": " + msg;
        buffer.addLast(line);
        while (buffer.size() > MAX_LINES) buffer.removeFirst();
        appendFile(line);
    }

    /** 落盘失败绝不影响业务：整段 try/catch，且日志目录不存在就静默跳过。 */
    private void appendFile(String line) {
        File f = file;
        if (f == null) return;
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            if (f.length() > MAX_FILE) {
                File old = new File(dir, f.getName() + ".1");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8")) {
                w.write(line);
                w.write('\n');
            }
        } catch (Throwable ignored) {
        }
    }

    /** 倒序（最新在前）导出，便于直接看最新日志。 */
    public synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        List<String> all = new ArrayList<>(buffer);
        java.util.Collections.reverse(all);
        for (String s : all) sb.append(s).append('\n');
        return sb.toString();
    }

    public synchronized void clear() {
        buffer.clear();
    }
}