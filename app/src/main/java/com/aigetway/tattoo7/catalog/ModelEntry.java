package com.aigetway.tattoo7.catalog;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 统一模型条目：跨服务商聚合，带完整能力数据，供 /v1/models 下发给 AG 客户端。
 */
public class ModelEntry {

    public final String id;
    public final String providerId;
    public final String displayName;
    public boolean vision;
    public boolean reasoning;
    public boolean tools = true;
    /** true = 免费模型（非付费）。 */
    public boolean free;
    /**
     * 计费倍率展示文本（如 "x0.08" / "x1" / 免费 "x0" / 促销 "x0.8→x0.08"；
     * AutoClaw 上游只给档位文案，原样透传 "低/中/高"）。
     * null = 上游没给倍率信息 —— 不显示，**不编造**（对齐参考实现的三条边界：
     * 无倍率只显示模型名；0 是合法倍率必须与「没有倍率信息」区分）。
     */
    public String rateText;
    public int contextWindow = 128000;
    public int maxOutput = 8192;
    /**
     * 思考档位（wire 值，保留下发顺序）；null = 未声明 —— toJson 时整个 reasoning
     * 视图字段缺省（对齐参考网关：「不知道」与「一个都没有」是两件事，绝不发空数组）。
     */
    public java.util.List<String> efforts;
    /** 默认思考档；仅当它落在 {@link #efforts} 内才有意义，否则保持 null。 */
    public String defaultEffort;
    /** 远程实时取到（true）还是本地兜底表（false）。 */
    public boolean live;

    public ModelEntry(String providerId, String id, String displayName) {
        this.providerId = providerId;
        this.id = id;
        this.displayName = displayName;
    }

    public static ModelEntry fallback(String providerId, String id, int ctx, int maxOut, boolean vision) {
        ModelEntry e = new ModelEntry(providerId, id, id);
        e.contextWindow = ctx;
        e.maxOutput = maxOut;
        e.vision = vision;
        e.live = false;
        return e;
    }

    /** 下发给 OpenAI 兼容客户端的模型对象（含能力字段）。 */
    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("object", "model");
        o.put("created", System.currentTimeMillis() / 1000L);
        o.put("owned_by", providerId);
        if (displayName != null && !displayName.equals(id)) o.put("display_name", displayName);
        o.put("vision", vision);
        o.put("reasoning", reasoning);
        o.put("tools", tools);
        o.put("context_window", contextWindow);
        o.put("max_output_tokens", maxOutput);
        // 部分 AG 客户端读这两个别名
        o.put("max_input_tokens", contextWindow);
        o.put("supports_vision", vision);
        // 计费倍率（vc161 起下发）：rate = 上游展示文本（促销形如 "x1→x0.08"，AutoClaw 为
        // 档位文案 "低/中/高"）；multiplier = 可解析出的**数值**（促销取末段=实付值，
        // 免费模型为 0）。上游没给倍率就不下发这两个键，绝不编造 x1。
        if (rateText != null && !rateText.trim().isEmpty()) {
            String rt = rateText.trim();
            o.put("rate", rt);
            Double mult = com.aigetway.tattoo7.provider.ModelRate.numericOf(rt);
            if (mult != null) o.put("multiplier", mult);
        }
        // OpenAI / OpenRouter 标准模态声明。Minis 等客户端**只读**
        // architecture.input_modalities 判定模型是否支持图像，不读顶层 vision/
        // supports_vision —— 缺这个字段时客户端把图降级成纯文本路径，服务端模型
        // 永远收不到图（CodeArts7 vc75～vc80「不收图」的根因）。视觉模型标
        // ["text","image"]，Minis 拉取后自动判定 hasImageInput=true → 以
        // image_url(data: base64) 发图 → 网关走上游官方附件通道。
        JSONArray ins = new JSONArray();
        ins.put("text");
        if (vision) ins.put("image");
        JSONObject arch = new JSONObject();
        arch.put("input_modalities", ins);
        arch.put("output_modalities", new JSONArray().put("text"));
        o.put("architecture", arch);
        // 参考网关（openai-gateway/models.ts）的 AG 客户端只认这三个键：
        // - 顶层数组 input：模态声明（空则按 ['text']）
        // - max_tokens：输出上限别名
        // - reasoning：思考档位机读视图 {efforts:[{id,name}], default, openai_efforts}
        //   （模型没声明档位时整个字段缺省 —— 发空数组会让用户误以为「没有档位」）
        o.put("input", ins);
        o.put("max_tokens", maxOutput);
        if (efforts != null && !efforts.isEmpty()) {
            JSONArray rows = new JSONArray();
            JSONArray openai = new JSONArray();
            for (String ef : efforts) {
                if (ef == null || ef.trim().isEmpty()) continue;
                JSONObject row = new JSONObject();
                row.put("id", ef);
                row.put("name", ef);
                rows.put(row);
                openai.put(ef);
            }
            if (rows.length() > 0) {
                JSONObject rv = new JSONObject();
                rv.put("efforts", rows);
                rv.put("openai_efforts", openai);
                if (defaultEffort != null && efforts.contains(defaultEffort)) {
                    rv.put("default", defaultEffort);
                }
                o.put("reasoning", rv);
            }
        }
        return o;
    }

    /** 能力标签，用于 UI 展示。 */
    public String capTags() {
        StringBuilder sb = new StringBuilder();
        if (vision) sb.append("视觉 ");
        if (reasoning) sb.append("推理 ");
        sb.append("ctx ").append(contextWindow >= 1000 ? (contextWindow / 1000) + "k" : String.valueOf(contextWindow));
        sb.append(" / out ").append(maxOutput >= 1000 ? (maxOutput / 1000) + "k" : String.valueOf(maxOutput));
        if (!live) sb.append(" 兜底");
        return sb.toString();
    }
}