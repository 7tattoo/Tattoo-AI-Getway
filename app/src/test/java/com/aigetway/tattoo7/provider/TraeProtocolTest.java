package com.aigetway.tattoo7.provider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;

/** 纯 JVM 的 TRAE 目录/请求/SSE 回归测试，不访问网络。 */
public class TraeProtocolTest {
    private static int passed;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }

    private static String delta(List<String> chunks, String field) throws Exception {
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            JSONArray choices = new JSONObject(chunk).getJSONArray("choices");
            JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
            out.append(delta.optString(field, ""));
        }
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        JSONObject custom = new JSONObject()
                .put("config_name", "custom-visible-only")
                .put("display_config", new JSONObject().put("is_custom_model", true));
        check(!TraeProvider.passesTraeFilters(custom), "nested custom model must be filtered");

        JSONObject remote = new JSONObject().put("function", "solo_agent_remote")
                .put("config_info_list", new JSONArray().put(new JSONObject()
                        .put("config_name", "agent-model")
                        .put("context_window_tokens", new JSONObject().put("dev", 200000))
                        .put("display_config", new JSONObject().put("multimodal", true))));
        java.util.ArrayList<com.aigetway.tattoo7.catalog.ModelEntry> models = new java.util.ArrayList<>();
        TraeProvider.MODEL_CHANNELS.clear();
        Method collect = TraeProvider.class.getDeclaredMethod("collect", Object.class, List.class, String.class);
        collect.setAccessible(true);
        collect.invoke(null, remote, models, "");
        check(models.size() == 1, "normal model retained");
        check(models.get(0).vision, "nested multimodal capability retained");
        check("solo_agent_remote".equals(TraeProvider.channelFor("agent-model")), "directory channel routed");
        check("solo_work_lite".equals(TraeProvider.channelFor("fallback-model")), "fallback channel");

        SseTranslator.Trae trae = new SseTranslator.Trae("agent-model");
        check(trae.translate("progress_notice", "Processing_1792911069", "").isEmpty(),
                "bare progress string must not reach client");
        check(trae.translate("progress_notice", ";Processing_1792910793571897789_KNZJy2", "").isEmpty(),
                "prefixed progress string must not reach client");
        check(trae.translate("unknown_control", "Processing_1792911069", "").isEmpty(),
                "unknown bare string must not be forwarded");
        List<String> output = trae.translate("output",
                new JSONObject().put("response", "正文").put("reasoning_content", "思考").toString(), "");
        check(output.size() == 2, "reasoning and content are independent chunks");
        check("思考".equals(delta(output, "reasoning_content")), "reasoning delta preserved");
        check("正文".equals(delta(output, "content")), "content delta preserved");
        List<String> reasoningOnly = trae.translate("output",
                new JSONObject().put("response", "").put("reasoning_content", "仅思考").toString(), "");
        check("仅思考".equals(delta(reasoningOnly, "reasoning_content")), "reasoning-only output preserved");
        check(trae.diagSummary() == null, "reasoning-only stream is not logged as empty");
        System.out.println("TraeProtocolTest: " + passed + " checks passed");
    }
}
