package com.aigetway.tattoo7.util;

/**
 * AStudio 扫码/登录链路的**内存诊断记录**：qrcode → poll → bind → completeLogin
 * 每一跳的原始响应摘要都记在这里，出错时随错误信息一起展示（登录失败弹窗尾部、
 * 登录页右上角「诊断」入口、{@code GET /astudio/diag}）。
 *
 * <p>仅内存、上限 8000 字符（超了清空重来），进程退出即消失；不落盘、不外发。
 */
public final class QrDiagnostics {

    private static final StringBuilder LOG = new StringBuilder();
    private static int count;

    private QrDiagnostics() {
    }

    /** 记一行诊断（时间序，超限自动清空重记）。 */
    public static synchronized void record(String line) {
        if (line == null || line.isEmpty()) return;
        if (LOG.length() > 8000) {
            LOG.setLength(0);
        }
        LOG.append(line).append('\n');
        count++;
    }

    /** 当前全部记录（给人看的文本）。 */
    public static synchronized String summaryText() {
        if (count == 0) return "（暂无诊断记录）";
        return "最近 " + count + " 条：\n" + LOG;
    }
}
