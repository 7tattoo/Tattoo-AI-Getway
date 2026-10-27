package dev.raccoon2api.provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import kotlin.Metadata;
import kotlin.collections.CollectionsKt;
import kotlin.collections.IntIterator;
import kotlin.io.ConstantsKt;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.ranges.IntRange;
import kotlin.ranges.RangesKt;
import kotlin.uuid.Uuid;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* compiled from: RaccoonModels.kt */
@Metadata(d1 = {"\u00008\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0003\n\u0002\u0010 \n\u0000\n\u0002\u0010\t\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0002\b\u0015\n\u0002\u0018\u0002\n\u0002\b\u0011\n\u0002\u0010\b\n\u0002\b\u0003\b\u0086\b\u0018\u0000 62\u00020\u0001:\u00016Ba\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0003\u0012\u0006\u0010\u0005\u001a\u00020\u0003\u0012\f\u0010\u0006\u001a\b\u0012\u0004\u0012\u00020\u00030\u0007\u0012\b\u0010\b\u001a\u0004\u0018\u00010\t\u0012\b\u0010\n\u001a\u0004\u0018\u00010\t\u0012\u0006\u0010\u000b\u001a\u00020\f\u0012\u0006\u0010\r\u001a\u00020\f\u0012\u0006\u0010\u000e\u001a\u00020\u0003\u0012\u0006\u0010\u000f\u001a\u00020\f¢\u0006\u0004\b\u0010\u0010\u0011J\u000e\u0010!\u001a\u00020\"2\u0006\u0010#\u001a\u00020\u0003J\u0006\u0010$\u001a\u00020\"J\t\u0010%\u001a\u00020\u0003HÆ\u0003J\t\u0010&\u001a\u00020\u0003HÆ\u0003J\t\u0010'\u001a\u00020\u0003HÆ\u0003J\u000f\u0010(\u001a\b\u0012\u0004\u0012\u00020\u00030\u0007HÆ\u0003J\u0010\u0010)\u001a\u0004\u0018\u00010\tHÆ\u0003¢\u0006\u0002\u0010\u0019J\u0010\u0010*\u001a\u0004\u0018\u00010\tHÆ\u0003¢\u0006\u0002\u0010\u0019J\t\u0010+\u001a\u00020\fHÆ\u0003J\t\u0010,\u001a\u00020\fHÆ\u0003J\t\u0010-\u001a\u00020\u0003HÆ\u0003J\t\u0010.\u001a\u00020\fHÆ\u0003J|\u0010/\u001a\u00020\u00002\b\b\u0002\u0010\u0002\u001a\u00020\u00032\b\b\u0002\u0010\u0004\u001a\u00020\u00032\b\b\u0002\u0010\u0005\u001a\u00020\u00032\u000e\b\u0002\u0010\u0006\u001a\b\u0012\u0004\u0012\u00020\u00030\u00072\n\b\u0002\u0010\b\u001a\u0004\u0018\u00010\t2\n\b\u0002\u0010\n\u001a\u0004\u0018\u00010\t2\b\b\u0002\u0010\u000b\u001a\u00020\f2\b\b\u0002\u0010\r\u001a\u00020\f2\b\b\u0002\u0010\u000e\u001a\u00020\u00032\b\b\u0002\u0010\u000f\u001a\u00020\fHÆ\u0001¢\u0006\u0002\u00100J\u0013\u00101\u001a\u00020\f2\b\u00102\u001a\u0004\u0018\u00010\u0001HÖ\u0003J\t\u00103\u001a\u000204HÖ\u0001J\t\u00105\u001a\u00020\u0003HÖ\u0001R\u0011\u0010\u0002\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u0012\u0010\u0013R\u0011\u0010\u0004\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u0014\u0010\u0013R\u0011\u0010\u0005\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u0015\u0010\u0013R\u0017\u0010\u0006\u001a\b\u0012\u0004\u0012\u00020\u00030\u0007¢\u0006\b\n\u0000\u001a\u0004\b\u0016\u0010\u0017R\u0015\u0010\b\u001a\u0004\u0018\u00010\t¢\u0006\n\n\u0002\u0010\u001a\u001a\u0004\b\u0018\u0010\u0019R\u0015\u0010\n\u001a\u0004\u0018\u00010\t¢\u0006\n\n\u0002\u0010\u001a\u001a\u0004\b\u001b\u0010\u0019R\u0011\u0010\u000b\u001a\u00020\f¢\u0006\b\n\u0000\u001a\u0004\b\u001c\u0010\u001dR\u0011\u0010\r\u001a\u00020\f¢\u0006\b\n\u0000\u001a\u0004\b\u001e\u0010\u001dR\u0011\u0010\u000e\u001a\u00020\u0003¢\u0006\b\n\u0000\u001a\u0004\b\u001f\u0010\u0013R\u0011\u0010\u000f\u001a\u00020\f¢\u0006\b\n\u0000\u001a\u0004\b \u0010\u001d¨\u00067"}, d2 = {"Ldev/raccoon2api/provider/ModelInfo;", "", "id", "", "name", "description", "tags", "", "contextWindow", "", "maxTokens", "supportsImages", "", "supportsReasoning", "credits", "visible", "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/lang/Long;Ljava/lang/Long;ZZLjava/lang/String;Z)V", "getId", "()Ljava/lang/String;", "getName", "getDescription", "getTags", "()Ljava/util/List;", "getContextWindow", "()Ljava/lang/Long;", "Ljava/lang/Long;", "getMaxTokens", "getSupportsImages", "()Z", "getSupportsReasoning", "getCredits", "getVisible", "toOpenAI", "Lorg/json/JSONObject;", "ownedBy", "toJson", "component1", "component2", "component3", "component4", "component5", "component6", "component7", "component8", "component9", "component10", "copy", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/lang/Long;Ljava/lang/Long;ZZLjava/lang/String;Z)Ldev/raccoon2api/provider/ModelInfo;", "equals", "other", "hashCode", "", "toString", "Companion", "app_release"}, k = 1, mv = {2, 0, 0}, xi = 48)
/* loaded from: /tmp/xhx/apk/classes.dex */
public final /* data */ class ModelInfo {

    /* renamed from: Companion, reason: from kotlin metadata */
    public static final Companion INSTANCE = new Companion(null);
    private final Long contextWindow;
    private final String credits;
    private final String description;
    private final String id;
    private final Long maxTokens;
    private final String name;
    private final boolean supportsImages;
    private final boolean supportsReasoning;
    private final List<String> tags;
    private final boolean visible;

    public static /* synthetic */ ModelInfo copy$default(ModelInfo modelInfo, String str, String str2, String str3, List list, Long l, Long l2, boolean z, boolean z2, String str4, boolean z3, int i, Object obj) {
        if ((i & 1) != 0) {
            str = modelInfo.id;
        }
        if ((i & 2) != 0) {
            str2 = modelInfo.name;
        }
        if ((i & 4) != 0) {
            str3 = modelInfo.description;
        }
        if ((i & 8) != 0) {
            list = modelInfo.tags;
        }
        if ((i & 16) != 0) {
            l = modelInfo.contextWindow;
        }
        if ((i & 32) != 0) {
            l2 = modelInfo.maxTokens;
        }
        if ((i & 64) != 0) {
            z = modelInfo.supportsImages;
        }
        if ((i & Uuid.SIZE_BITS) != 0) {
            z2 = modelInfo.supportsReasoning;
        }
        if ((i & 256) != 0) {
            str4 = modelInfo.credits;
        }
        if ((i & ConstantsKt.MINIMUM_BLOCK_SIZE) != 0) {
            z3 = modelInfo.visible;
        }
        String str5 = str4;
        boolean z4 = z3;
        boolean z5 = z;
        boolean z6 = z2;
        Long l3 = l;
        Long l4 = l2;
        return modelInfo.copy(str, str2, str3, list, l3, l4, z5, z6, str5, z4);
    }

    /* renamed from: component1, reason: from getter */
    public final String getId() {
        return this.id;
    }

    /* renamed from: component10, reason: from getter */
    public final boolean getVisible() {
        return this.visible;
    }

    /* renamed from: component2, reason: from getter */
    public final String getName() {
        return this.name;
    }

    /* renamed from: component3, reason: from getter */
    public final String getDescription() {
        return this.description;
    }

    public final List<String> component4() {
        return this.tags;
    }

    /* renamed from: component5, reason: from getter */
    public final Long getContextWindow() {
        return this.contextWindow;
    }

    /* renamed from: component6, reason: from getter */
    public final Long getMaxTokens() {
        return this.maxTokens;
    }

    /* renamed from: component7, reason: from getter */
    public final boolean getSupportsImages() {
        return this.supportsImages;
    }

    /* renamed from: component8, reason: from getter */
    public final boolean getSupportsReasoning() {
        return this.supportsReasoning;
    }

    /* renamed from: component9, reason: from getter */
    public final String getCredits() {
        return this.credits;
    }

    public final ModelInfo copy(String id, String name, String description, List<String> tags, Long contextWindow, Long maxTokens, boolean supportsImages, boolean supportsReasoning, String credits, boolean visible) {
        Intrinsics.checkNotNullParameter(id, "id");
        Intrinsics.checkNotNullParameter(name, "name");
        Intrinsics.checkNotNullParameter(description, "description");
        Intrinsics.checkNotNullParameter(tags, "tags");
        Intrinsics.checkNotNullParameter(credits, "credits");
        return new ModelInfo(id, name, description, tags, contextWindow, maxTokens, supportsImages, supportsReasoning, credits, visible);
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModelInfo)) {
            return false;
        }
        ModelInfo modelInfo = (ModelInfo) other;
        return Intrinsics.areEqual(this.id, modelInfo.id) && Intrinsics.areEqual(this.name, modelInfo.name) && Intrinsics.areEqual(this.description, modelInfo.description) && Intrinsics.areEqual(this.tags, modelInfo.tags) && Intrinsics.areEqual(this.contextWindow, modelInfo.contextWindow) && Intrinsics.areEqual(this.maxTokens, modelInfo.maxTokens) && this.supportsImages == modelInfo.supportsImages && this.supportsReasoning == modelInfo.supportsReasoning && Intrinsics.areEqual(this.credits, modelInfo.credits) && this.visible == modelInfo.visible;
    }

    public int hashCode() {
        int iHashCode = ((((((this.id.hashCode() * 31) + this.name.hashCode()) * 31) + this.description.hashCode()) * 31) + this.tags.hashCode()) * 31;
        Long l = this.contextWindow;
        int iHashCode2 = (iHashCode + (l == null ? 0 : l.hashCode())) * 31;
        Long l2 = this.maxTokens;
        return ((((((((iHashCode2 + (l2 != null ? l2.hashCode() : 0)) * 31) + Boolean.hashCode(this.supportsImages)) * 31) + Boolean.hashCode(this.supportsReasoning)) * 31) + this.credits.hashCode()) * 31) + Boolean.hashCode(this.visible);
    }

    public String toString() {
        return "ModelInfo(id=" + this.id + ", name=" + this.name + ", description=" + this.description + ", tags=" + this.tags + ", contextWindow=" + this.contextWindow + ", maxTokens=" + this.maxTokens + ", supportsImages=" + this.supportsImages + ", supportsReasoning=" + this.supportsReasoning + ", credits=" + this.credits + ", visible=" + this.visible + ")";
    }

    public ModelInfo(String id, String name, String description, List<String> tags, Long l, Long l2, boolean z, boolean z2, String credits, boolean z3) {
        Intrinsics.checkNotNullParameter(id, "id");
        Intrinsics.checkNotNullParameter(name, "name");
        Intrinsics.checkNotNullParameter(description, "description");
        Intrinsics.checkNotNullParameter(tags, "tags");
        Intrinsics.checkNotNullParameter(credits, "credits");
        this.id = id;
        this.name = name;
        this.description = description;
        this.tags = tags;
        this.contextWindow = l;
        this.maxTokens = l2;
        this.supportsImages = z;
        this.supportsReasoning = z2;
        this.credits = credits;
        this.visible = z3;
    }

    public final String getId() {
        return this.id;
    }

    public final String getName() {
        return this.name;
    }

    public final String getDescription() {
        return this.description;
    }

    public final List<String> getTags() {
        return this.tags;
    }

    public final Long getContextWindow() {
        return this.contextWindow;
    }

    public final Long getMaxTokens() {
        return this.maxTokens;
    }

    public final boolean getSupportsImages() {
        return this.supportsImages;
    }

    public final boolean getSupportsReasoning() {
        return this.supportsReasoning;
    }

    public final String getCredits() {
        return this.credits;
    }

    public final boolean getVisible() {
        return this.visible;
    }

    public final JSONObject toOpenAI(String ownedBy) throws JSONException {
        Intrinsics.checkNotNullParameter(ownedBy, "ownedBy");
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("id", this.id);
        jSONObject.put("object", "model");
        jSONObject.put("created", 0);
        jSONObject.put("owned_by", ownedBy);
        jSONObject.put("name", this.name);
        jSONObject.put("description", this.description);
        jSONObject.put("tags", CollectionsKt.joinToString$default(this.tags, ",", null, null, 0, null, null, 62, null));
        Long l = this.contextWindow;
        if (l != null) {
            jSONObject.put("maxInputTokens", l.longValue());
        }
        Long l2 = this.maxTokens;
        if (l2 != null) {
            jSONObject.put("maxOutputTokens", l2.longValue());
        }
        jSONObject.put("supportsImages", this.supportsImages);
        jSONObject.put("supportsReasoning", this.supportsReasoning);
        jSONObject.put("credits", this.credits);
        return jSONObject;
    }

    public final JSONObject toJson() throws JSONException {
        JSONObject jSONObject = new JSONObject();
        jSONObject.put("id", this.id);
        jSONObject.put("name", this.name);
        jSONObject.put("description", this.description);
        jSONObject.put("tags", new JSONArray((Collection) this.tags));
        Long l = this.contextWindow;
        if (l != null) {
            jSONObject.put("contextWindow", l.longValue());
        }
        Long l2 = this.maxTokens;
        if (l2 != null) {
            jSONObject.put("maxTokens", l2.longValue());
        }
        jSONObject.put("supportsImages", this.supportsImages);
        jSONObject.put("supportsReasoning", this.supportsReasoning);
        jSONObject.put("credits", this.credits);
        jSONObject.put("visible", this.visible);
        return jSONObject;
    }

    /* compiled from: RaccoonModels.kt */
    @Metadata(d1 = {"\u0000\u0018\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\t\b\u0002¢\u0006\u0004\b\u0002\u0010\u0003J\u000e\u0010\u0004\u001a\u00020\u00052\u0006\u0010\u0006\u001a\u00020\u0007¨\u0006\b"}, d2 = {"Ldev/raccoon2api/provider/ModelInfo$Companion;", "", "<init>", "()V", "fromJson", "Ldev/raccoon2api/provider/ModelInfo;", "json", "Lorg/json/JSONObject;", "app_release"}, k = 1, mv = {2, 0, 0}, xi = 48)
    public static final class Companion {
        public /* synthetic */ Companion(DefaultConstructorMarker defaultConstructorMarker) {
            this();
        }

        private Companion() {
        }

        public final ModelInfo fromJson(JSONObject json) {
            ArrayList arrayListEmptyList;
            Intrinsics.checkNotNullParameter(json, "json");
            String strOptString = json.optString("id");
            Intrinsics.checkNotNullExpressionValue(strOptString, "optString(...)");
            String strOptString2 = json.optString("name");
            Intrinsics.checkNotNullExpressionValue(strOptString2, "optString(...)");
            String strOptString3 = json.optString("description");
            Intrinsics.checkNotNullExpressionValue(strOptString3, "optString(...)");
            JSONArray jSONArrayOptJSONArray = json.optJSONArray("tags");
            boolean z = false;
            if (jSONArrayOptJSONArray != null) {
                IntRange intRangeUntil = RangesKt.until(0, jSONArrayOptJSONArray.length());
                ArrayList arrayList = new ArrayList();
                Iterator<Integer> it = intRangeUntil.iterator();
                while (it.hasNext()) {
                    String strOptString4 = jSONArrayOptJSONArray.optString(((IntIterator) it).nextInt());
                    Intrinsics.checkNotNull(strOptString4);
                    if (strOptString4.length() <= 0) {
                        strOptString4 = null;
                    }
                    if (strOptString4 != null) {
                        arrayList.add(strOptString4);
                    }
                }
                arrayListEmptyList = arrayList;
            } else {
                arrayListEmptyList = CollectionsKt.emptyList();
            }
            Object objOpt = json.opt("contextWindow");
            if (!Boolean.valueOf((objOpt == null || Intrinsics.areEqual(objOpt, JSONObject.NULL)) ? false : true).booleanValue()) {
                objOpt = null;
            }
            Long lValueOf = objOpt != null ? Long.valueOf(((Number) objOpt).longValue()) : null;
            Object objOpt2 = json.opt("maxTokens");
            if (objOpt2 != null && !Intrinsics.areEqual(objOpt2, JSONObject.NULL)) {
                z = true;
            }
            if (!Boolean.valueOf(z).booleanValue()) {
                objOpt2 = null;
            }
            Long lValueOf2 = objOpt2 != null ? Long.valueOf(((Number) objOpt2).longValue()) : null;
            boolean zOptBoolean = json.optBoolean("supportsImages");
            boolean zOptBoolean2 = json.optBoolean("supportsReasoning");
            String strOptString5 = json.optString("credits");
            Intrinsics.checkNotNullExpressionValue(strOptString5, "optString(...)");
            return new ModelInfo(strOptString, strOptString2, strOptString3, arrayListEmptyList, lValueOf, lValueOf2, zOptBoolean, zOptBoolean2, strOptString5, json.optBoolean("visible", true));
        }
    }
}
