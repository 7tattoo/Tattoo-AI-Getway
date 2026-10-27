package dev.raccoon2api.provider;

import kotlin.Metadata;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;

/* compiled from: RaccoonProvider.kt */
@Metadata(d1 = {"\u0000\"\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\t\n\u0002\b\b\u0018\u00002\u00060\u0001j\u0002`\u0002B#\u0012\u0006\u0010\u0003\u001a\u00020\u0004\u0012\u0006\u0010\u0005\u001a\u00020\u0006\u0012\n\b\u0002\u0010\u0007\u001a\u0004\u0018\u00010\b¢\u0006\u0004\b\t\u0010\nR\u0011\u0010\u0003\u001a\u00020\u0004¢\u0006\b\n\u0000\u001a\u0004\b\u000b\u0010\fR\u0015\u0010\u0007\u001a\u0004\u0018\u00010\b¢\u0006\n\n\u0002\u0010\u000f\u001a\u0004\b\r\u0010\u000e¨\u0006\u0010"}, d2 = {"Ldev/raccoon2api/provider/UpstreamError;", "Ljava/lang/Exception;", "Lkotlin/Exception;", "status", "", "message", "", "upstreamCode", "", "<init>", "(ILjava/lang/String;Ljava/lang/Long;)V", "getStatus", "()I", "getUpstreamCode", "()Ljava/lang/Long;", "Ljava/lang/Long;", "app_release"}, k = 1, mv = {2, 0, 0}, xi = 48)
/* loaded from: /tmp/xhx/apk/classes.dex */
public final class UpstreamError extends Exception {
    private final int status;
    private final Long upstreamCode;

    public /* synthetic */ UpstreamError(int i, String str, Long l, int i2, DefaultConstructorMarker defaultConstructorMarker) {
        this(i, str, (i2 & 4) != 0 ? null : l);
    }

    public final int getStatus() {
        return this.status;
    }

    public final Long getUpstreamCode() {
        return this.upstreamCode;
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public UpstreamError(int i, String message, Long l) {
        super(message);
        Intrinsics.checkNotNullParameter(message, "message");
        this.status = i;
        this.upstreamCode = l;
    }
}
