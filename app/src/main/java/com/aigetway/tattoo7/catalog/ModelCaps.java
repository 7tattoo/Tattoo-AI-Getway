package com.aigetway.tattoo7.catalog;

/** 上游未给能力字段时的推断表（按模型名族），保证 /v1/models 始终带完整能力。 */
public final class ModelCaps {

    private ModelCaps() {}

    /** 按模型 id 推断能力，就地补全 e（仅在缺省时填）。 */
    public static void enrich(ModelEntry e) {
        String id = e.id == null ? "" : e.id.toLowerCase(java.util.Locale.US);

        // 视觉模型：显式含 v/omni/vision/glm-5v/pixtral/qwen-vl/gpt-4o/4.1/5
        boolean vision = id.contains("-vl") || id.contains("vision") || id.contains("5v")
                || id.contains("omni") || id.startsWith("gpt-4o") || id.startsWith("gpt-5")
                || id.contains("pixtral") || id.contains("glm-4v");
        // 推理模型：deepseek / glm-5 / kimi / qwen3 / hy / minimax-m / gpt-5 / o1 / r1
        //（AStudio slug 族：xopglm52 / xopdsv4flash0731in / spark-x2.5 / 2.6 / astronclaw-auto）
        boolean reasoning = id.contains("deepseek") || id.contains("reason") || id.startsWith("r1")
                || id.contains("glm-5") || id.contains("glm5") || id.contains("dsv4")
                || id.contains("spark-x") || id.contains("astronclaw") || id.contains("kimi")
                || id.contains("kimi-k") || id.contains("qwen3")
                || id.startsWith("hy") || id.contains("minimax-m") || id.startsWith("gpt-5")
                || id.startsWith("o1") || id.startsWith("o3");

        if (e.contextWindow <= 0) {
            if (id.contains("glm-5") || id.contains("glm5") || id.contains("dsv4")
                    || id.contains("astronclaw") || id.contains("sn-glm-5-3") || id.contains("kimi-k3")) e.contextWindow = 1000000;
            else if (id.contains("spark-x")) e.contextWindow = 262144;
            else if (id.contains("sensenova")) e.contextWindow = 256000;
            else e.contextWindow = 128000;
        }
        if (e.maxOutput <= 0) {
            if (id.contains("dsv4") || id.contains("astronclaw")) e.maxOutput = 384000;
            else if (id.contains("glm5")) e.maxOutput = 128000;
            else if (id.contains("glm-5") || id.contains("kimi-k3") || id.contains("sensenova")) e.maxOutput = 63999;
            else if (id.contains("spark-x")) e.maxOutput = 256000;
            else e.maxOutput = 8192;
        }
        e.vision = e.vision || vision;
        e.reasoning = e.reasoning || reasoning;
    }
}
