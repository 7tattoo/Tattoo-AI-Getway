package com.aigetway.tattoo7.provider;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;

/** 上游 Provider 接口。 */
public interface Provider {

    /** 模型目录（本地兜底清单）。 */
    String[] modelIds();

    /** 打开一个上游 chat 会话，把 SSE 流透传到 out。 */
    void openChat(JSONObject body, OutputStream out) throws IOException;

    /** 从上游拉取模型目录（可能失败，失败时回退 modelIds）。 */
    String[] fetchModels() throws IOException;

    /** /v1/models 的 JSON data 数组（含能力字段）。 */
    org.json.JSONArray modelListJson();
}