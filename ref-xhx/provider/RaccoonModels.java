package dev.raccoon2api.provider;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import kotlin.Metadata;
import kotlin.collections.CollectionsKt;
import kotlin.collections.IntIterator;
import kotlin.io.FilesKt;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.StringCompanionObject;
import kotlin.ranges.IntRange;
import kotlin.ranges.RangesKt;
import kotlin.text.StringsKt;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* compiled from: RaccoonModels.kt */
@Metadata(d1 = {"\u0000L\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\t\n\u0000\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0003\n\u0002\u0010\u000b\n\u0002\b\u0003\n\u0002\u0010\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0010\u0006\n\u0002\b\u0004\bÆ\u0002\u0018\u00002\u00020\u0001B\t\b\u0002¢\u0006\u0004\b\u0002\u0010\u0003J\u0006\u0010\f\u001a\u00020\rJ\f\u0010\u000e\u001a\b\u0012\u0004\u0012\u00020\b0\u0007J\f\u0010\u000f\u001a\b\u0012\u0004\u0012\u00020\b0\u0007J\u0006\u0010\u0010\u001a\u00020\u0011J\u0006\u0010\u0012\u001a\u00020\u0005J\u0006\u0010\u0013\u001a\u00020\u0011J\u0014\u0010\u0014\u001a\u00020\u00152\f\u0010\u0016\u001a\b\u0012\u0004\u0012\u00020\b0\u0007J\u000e\u0010\u0017\u001a\u00020\u00152\u0006\u0010\u0018\u001a\u00020\u000bJ\b\u0010\u0019\u001a\u00020\u0015H\u0002J\u0006\u0010\u001a\u001a\u00020\u0015J\u0014\u0010\u001b\u001a\b\u0012\u0004\u0012\u00020\b0\u00072\u0006\u0010\u001c\u001a\u00020\u001dJ\u0012\u0010\u001e\u001a\u0004\u0018\u00010\b2\u0006\u0010\u001f\u001a\u00020\u001dH\u0002J\u0018\u0010 \u001a\u00020\r2\u0006\u0010!\u001a\u00020\u001d2\u0006\u0010\"\u001a\u00020\rH\u0002J\u0019\u0010#\u001a\u0004\u0018\u00010$2\b\u0010%\u001a\u0004\u0018\u00010\u0001H\u0002¢\u0006\u0002\u0010&J\u0010\u0010'\u001a\u00020\r2\u0006\u0010%\u001a\u00020$H\u0002R\u000e\u0010\u0004\u001a\u00020\u0005X\u0086T¢\u0006\u0002\n\u0000R\u0016\u0010\u0006\u001a\n\u0012\u0004\u0012\u00020\b\u0018\u00010\u0007X\u0082\u000e¢\u0006\u0002\n\u0000R\u000e\u0010\t\u001a\u00020\u0005X\u0082\u000e¢\u0006\u0002\n\u0000R\u0010\u0010\n\u001a\u0004\u0018\u00010\u000bX\u0082\u000e¢\u0006\u0002\n\u0000¨\u0006("}, d2 = {"Ldev/raccoon2api/provider/RaccoonModels;", "", "<init>", "()V", "CATALOG_TTL_MS", "", "remote", "", "Ldev/raccoon2api/provider/ModelInfo;", "fetchedAt", "persistenceFile", "Ljava/io/File;", "defaultModelId", "", "fallbackModels", "current", "remoteRefreshed", "", "lastRefreshedAt", "fresh", "apply", "", "list", "init", "dir", "persist", "reset", "parseCatalog", "payload", "Lorg/json/JSONObject;", "normalizeEntry", "entry", "textOf", "obj", "key", "numberOf", "", "value", "(Ljava/lang/Object;)Ljava/lang/Double;", "formatMultiplier", "app_release"}, k = 1, mv = {2, 0, 0}, xi = 48)
/* loaded from: /tmp/xhx/apk/classes.dex */
public final class RaccoonModels {
    public static final long CATALOG_TTL_MS = 600000;
    public static final RaccoonModels INSTANCE = new RaccoonModels();
    private static long fetchedAt;
    private static File persistenceFile;
    private static List<ModelInfo> remote;

    private RaccoonModels() {
    }

    public final String defaultModelId() {
        return "raccoon-chat-ml-5-5";
    }

    public final List<ModelInfo> fallbackModels() {
        return CollectionsKt.listOf((Object[]) new ModelInfo[]{new ModelInfo("raccoon-8c4485", "Raccoon-Work", "适合复杂办公任务、代码开发、调试和图像识别等任务（目录默认旗舰，自称 GPT-5）", CollectionsKt.listOf((Object[]) new String[]{"general", "office", "code", "vision"}), 1000000L, 100000L, true, false, "x1 credits", true), new ModelInfo("raccoon-19b265", "Raccoon-Work-260817-A", "适合复杂办公任务、代码开发和综合分析等任务（A/B 变体 A，自称 GPT-5）", CollectionsKt.listOf((Object[]) new String[]{"general", "office", "code", "analysis"}), 1000000L, 100000L, false, false, "x1 credits", true), new ModelInfo("raccoon-405a1c", "Raccoon-Work-260817-B", "适合通用对话、轻量代码和内容处理等任务（A/B 变体 B，自称 GPT-5）", CollectionsKt.listOf((Object[]) new String[]{"general", "chat", "rewrite", "summary", "fast"}), 1000000L, 100000L, false, false, "x1 credits", true), new ModelInfo(defaultModelId(), "Raccoon Chat (默认)", "小浣熊默认对话模型（default-llm-config.json 预置）。", CollectionsKt.emptyList(), 180000L, 80000L, false, true, "", true), new ModelInfo("sn-sensenova-6-8-flash-lite", "SenseNova 6.8 Flash Lite", "轻量模型，用于标题生成等小任务。", CollectionsKt.emptyList(), 256000L, 63999L, false, true, "x0.5 credits", true)});
    }

    public final synchronized List<ModelInfo> current() {
        List<ModelInfo> listFallbackModels;
        listFallbackModels = remote;
        if (listFallbackModels == null) {
            listFallbackModels = fallbackModels();
        }
        return listFallbackModels;
    }

    public final synchronized boolean remoteRefreshed() {
        return remote != null;
    }

    public final synchronized long lastRefreshedAt() {
        return fetchedAt;
    }

    /* JADX WARN: Removed duplicated region for block: B:9:0x0015  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    public final synchronized boolean fresh() {
        /*
            r4 = this;
            monitor-enter(r4)
            java.util.List<dev.raccoon2api.provider.ModelInfo> r0 = dev.raccoon2api.provider.RaccoonModels.remote     // Catch: java.lang.Throwable -> L18
            if (r0 == 0) goto L15
            long r0 = java.lang.System.currentTimeMillis()     // Catch: java.lang.Throwable -> L18
            long r2 = dev.raccoon2api.provider.RaccoonModels.fetchedAt     // Catch: java.lang.Throwable -> L18
            long r0 = r0 - r2
            r2 = 600000(0x927c0, double:2.964394E-318)
            int r0 = (r0 > r2 ? 1 : (r0 == r2 ? 0 : -1))
            if (r0 >= 0) goto L15
            r0 = 1
            goto L16
        L15:
            r0 = 0
        L16:
            monitor-exit(r4)
            return r0
        L18:
            r0 = move-exception
            monitor-exit(r4)     // Catch: java.lang.Throwable -> L18
            throw r0
        */
        throw new UnsupportedOperationException("Method not decompiled: dev.raccoon2api.provider.RaccoonModels.fresh():boolean");
    }

    public final synchronized void apply(List<ModelInfo> list) {
        Intrinsics.checkNotNullParameter(list, "list");
        remote = list;
        fetchedAt = System.currentTimeMillis();
        persist();
    }

    public final synchronized void init(File dir) {
        Intrinsics.checkNotNullParameter(dir, "dir");
        File file = new File(dir, "model_catalog.json");
        persistenceFile = file;
        if (file.exists()) {
            ArrayList arrayList = null;
            JSONObject jSONObject = new JSONObject(FilesKt.readText$default(file, null, 1, null));
            JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("models");
            if (jSONArrayOptJSONArray != null) {
                IntRange intRangeUntil = RangesKt.until(0, jSONArrayOptJSONArray.length());
                ArrayList arrayList2 = new ArrayList();
                Iterator<Integer> it = intRangeUntil.iterator();
                while (it.hasNext()) {
                    JSONObject jSONObjectOptJSONObject = jSONArrayOptJSONArray.optJSONObject(((IntIterator) it).nextInt());
                    ModelInfo modelInfoFromJson = jSONObjectOptJSONObject != null ? ModelInfo.INSTANCE.fromJson(jSONObjectOptJSONObject) : null;
                    if (modelInfoFromJson != null) {
                        arrayList2.add(modelInfoFromJson);
                    }
                }
                ArrayList arrayList3 = arrayList2;
                if (!arrayList3.isEmpty()) {
                    arrayList = arrayList3;
                }
            }
            remote = arrayList;
            fetchedAt = jSONObject.optLong("fetchedAt", System.currentTimeMillis());
        }
    }

    private final void persist() throws JSONException {
        List<ModelInfo> list;
        try {
            File file = persistenceFile;
            if (file != null && (list = remote) != null) {
                JSONArray jSONArray = new JSONArray();
                Iterator<ModelInfo> it = list.iterator();
                while (it.hasNext()) {
                    jSONArray.put(it.next().toJson());
                }
                JSONObject jSONObjectPut = new JSONObject().put("fetchedAt", fetchedAt).put("models", jSONArray);
                File file2 = new File(file.getParentFile(), file.getName() + ".tmp");
                String string = jSONObjectPut.toString();
                Intrinsics.checkNotNullExpressionValue(string, "toString(...)");
                FilesKt.writeText$default(file2, string, null, 2, null);
                if (file2.renameTo(file)) {
                    return;
                }
                String string2 = jSONObjectPut.toString();
                Intrinsics.checkNotNullExpressionValue(string2, "toString(...)");
                FilesKt.writeText$default(file, string2, null, 2, null);
            }
        } catch (Exception unused) {
        }
    }

    public final synchronized void reset() {
        remote = null;
        fetchedAt = 0L;
    }

    public final List<ModelInfo> parseCatalog(JSONObject payload) throws JSONException {
        JSONArray jSONArrayOptJSONArray;
        ModelInfo modelInfoNormalizeEntry;
        Intrinsics.checkNotNullParameter(payload, "payload");
        if (payload.has("data") && (payload.opt("data") instanceof JSONObject)) {
            payload = payload.getJSONObject("data");
        }
        JSONArray jSONArrayOptJSONArray2 = payload.optJSONArray("categories");
        if (jSONArrayOptJSONArray2 == null) {
            return CollectionsKt.emptyList();
        }
        HashSet hashSet = new HashSet();
        ArrayList arrayList = new ArrayList();
        int length = jSONArrayOptJSONArray2.length();
        for (int i = 0; i < length; i++) {
            JSONObject jSONObjectOptJSONObject = jSONArrayOptJSONArray2.optJSONObject(i);
            if (jSONObjectOptJSONObject != null && (jSONArrayOptJSONArray = jSONObjectOptJSONObject.optJSONArray("models")) != null) {
                int length2 = jSONArrayOptJSONArray.length();
                for (int i2 = 0; i2 < length2; i2++) {
                    JSONObject jSONObjectOptJSONObject2 = jSONArrayOptJSONArray.optJSONObject(i2);
                    if (jSONObjectOptJSONObject2 != null && (modelInfoNormalizeEntry = normalizeEntry(jSONObjectOptJSONObject2)) != null && hashSet.add(modelInfoNormalizeEntry.getId())) {
                        arrayList.add(modelInfoNormalizeEntry);
                    }
                }
            }
        }
        return arrayList;
    }

    private final ModelInfo normalizeEntry(JSONObject entry) {
        ArrayList arrayListEmptyList;
        boolean z;
        String str;
        String strTextOf = textOf(entry, "name");
        if (strTextOf.length() == 0) {
            strTextOf = INSTANCE.textOf(entry, "model_name");
        }
        String string = StringsKt.trim((CharSequence) strTextOf).toString();
        if (string.length() == 0) {
            return null;
        }
        JSONArray jSONArrayOptJSONArray = entry.optJSONArray("tags");
        if (jSONArrayOptJSONArray != null) {
            IntRange intRangeUntil = RangesKt.until(0, jSONArrayOptJSONArray.length());
            ArrayList arrayList = new ArrayList();
            Iterator<Integer> it = intRangeUntil.iterator();
            while (it.hasNext()) {
                String strOptString = jSONArrayOptJSONArray.optString(((IntIterator) it).nextInt());
                Intrinsics.checkNotNullExpressionValue(strOptString, "optString(...)");
                String string2 = StringsKt.trim((CharSequence) strOptString).toString();
                if (string2.length() <= 0) {
                    string2 = null;
                }
                if (string2 != null) {
                    arrayList.add(string2);
                }
            }
            arrayListEmptyList = arrayList;
        } else {
            arrayListEmptyList = CollectionsKt.emptyList();
        }
        JSONObject jSONObjectOptJSONObject = entry.optJSONObject("params");
        String string3 = StringsKt.trim((CharSequence) textOf(entry, "description")).toString();
        String strTextOf2 = textOf(entry, "display_name");
        if (strTextOf2.length() == 0) {
            if (string3.length() <= 0 || string3.length() > 64) {
                string3 = string;
            }
            strTextOf2 = string3;
        }
        String str2 = strTextOf2;
        String string4 = StringsKt.trim((CharSequence) textOf(entry, "display_description")).toString();
        List<String> list = arrayListEmptyList;
        if ((list instanceof Collection) && list.isEmpty()) {
            z = false;
        } else {
            for (String str3 : list) {
                if (Intrinsics.areEqual(str3, "vision") || Intrinsics.areEqual(str3, "image") || Intrinsics.areEqual(str3, "image-understanding")) {
                    z = true;
                    break;
                }
            }
            z = false;
        }
        Double dNumberOf = numberOf(entry.opt("billing_multiplier"));
        Double dNumberOf2 = numberOf(entry.opt("billing_effective_multiplier"));
        if (dNumberOf2 != null) {
            dNumberOf = dNumberOf2;
        }
        if (dNumberOf == null || (str = "x" + INSTANCE.formatMultiplier(dNumberOf.doubleValue()) + " credits") == null) {
            str = "";
        }
        String str4 = str;
        Object objOpt = entry.opt("visible");
        Object obj = objOpt instanceof Boolean ? objOpt : null;
        return new ModelInfo(string, str2, string4, arrayListEmptyList, normalizeEntry$pickInt(entry, jSONObjectOptJSONObject, "context_window", "contextWindow"), normalizeEntry$pickInt(entry, jSONObjectOptJSONObject, "max_tokens", "maxTokens"), z, true, str4, obj != null ? ((Boolean) obj).booleanValue() : true);
    }

    private static final Long normalizeEntry$pickInt(JSONObject jSONObject, JSONObject jSONObject2, String... strArr) {
        Object objOpt;
        Double doubleOrNull;
        double dDoubleValue;
        Double doubleOrNull2;
        double dDoubleValue2;
        for (String str : strArr) {
            Object objOpt2 = jSONObject.opt(str);
            if (objOpt2 != null) {
                if (objOpt2 instanceof Number) {
                    dDoubleValue2 = ((Number) objOpt2).doubleValue();
                } else if ((objOpt2 instanceof String) && (doubleOrNull2 = StringsKt.toDoubleOrNull((String) objOpt2)) != null) {
                    dDoubleValue2 = doubleOrNull2.doubleValue();
                }
                if (!Double.isInfinite(dDoubleValue2) && !Double.isNaN(dDoubleValue2)) {
                    return Long.valueOf((long) dDoubleValue2);
                }
            }
            if (jSONObject2 != null && (objOpt = jSONObject2.opt(str)) != null) {
                if (objOpt instanceof Number) {
                    dDoubleValue = ((Number) objOpt).doubleValue();
                } else if ((objOpt instanceof String) && (doubleOrNull = StringsKt.toDoubleOrNull((String) objOpt)) != null) {
                    dDoubleValue = doubleOrNull.doubleValue();
                }
                if (!Double.isInfinite(dDoubleValue) && !Double.isNaN(dDoubleValue)) {
                    return Long.valueOf((long) dDoubleValue);
                }
            }
        }
        return null;
    }

    private final String textOf(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key)) {
            return "";
        }
        Object objOpt = obj.opt(key);
        return objOpt instanceof String ? (String) objOpt : ((objOpt instanceof Number) || (objOpt instanceof Boolean)) ? objOpt.toString() : "";
    }

    private final Double numberOf(Object value) {
        Double doubleOrNull;
        double dDoubleValue;
        if (!(value instanceof Number)) {
            if ((value instanceof String) && (doubleOrNull = StringsKt.toDoubleOrNull((String) value)) != null) {
                dDoubleValue = doubleOrNull.doubleValue();
            }
            return null;
        }
        dDoubleValue = ((Number) value).doubleValue();
        if (!Double.isInfinite(dDoubleValue) && !Double.isNaN(dDoubleValue) && dDoubleValue >= 0.0d) {
            return Double.valueOf(dDoubleValue);
        }
        return null;
    }

    private final String formatMultiplier(double value) {
        double dRound = Math.round(value * 100.0d) / 100.0d;
        if (dRound == Math.floor(dRound)) {
            return String.valueOf((long) dRound);
        }
        StringCompanionObject stringCompanionObject = StringCompanionObject.INSTANCE;
        String str = String.format(Locale.US, "%.2f", Arrays.copyOf(new Object[]{Double.valueOf(dRound)}, 1));
        Intrinsics.checkNotNullExpressionValue(str, "format(...)");
        return StringsKt.trimEnd(StringsKt.trimEnd(str, '0'), '.');
    }
}
