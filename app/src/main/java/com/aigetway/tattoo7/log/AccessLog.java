package com.aigetway.tattoo7.log;

/** 一条访问日志：哪个服务商、哪个模型、被谁（key）调用、结果与耗时。 */
public class AccessLog {

    public final long at;
    public final String providerId;
    public final String providerName;
    public final String model;
    public final String path;
    public final String keyLabel;
    public final int status;
    public final long latencyMs;
    public final String note;

    public AccessLog(long at, String providerId, String providerName, String model,
                     String path, String keyLabel, int status, long latencyMs, String note) {
        this.at = at;
        this.providerId = providerId;
        this.providerName = providerName;
        this.model = model;
        this.path = path;
        this.keyLabel = keyLabel;
        this.status = status;
        this.latencyMs = latencyMs;
        this.note = note == null ? "" : note;
    }

    public String time() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
        return f.format(new java.util.Date(at));
    }

    public String line() {
        StringBuilder sb = new StringBuilder();
        sb.append(time()).append("  [").append(providerName).append("] ");
        sb.append(path).append("  model=").append(model == null || model.isEmpty() ? "-" : model);
        sb.append("  status=").append(status);
        if (latencyMs > 0) sb.append("  ").append(latencyMs).append("ms");
        if (!keyLabel.isEmpty()) sb.append("  key=").append(keyLabel);
        if (!note.isEmpty()) sb.append("  ").append(note);
        return sb.toString();
    }
}
