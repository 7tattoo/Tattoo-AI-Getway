import com.aigetway.tattoo7.provider.RunableAttach;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.jar.JarOutputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * 离线测试真实类 RunableAttach（MIME 推断 / metadata.files 字段名 / 失败项剔除）。
 * RunableProvider 的两个常量用反射从编译产物里取，避免为测试引入 Android 依赖。
 */
public class TestRunableAttachRouting {
    static int pass = 0, fail = 0;

    static void eq(Object a, Object b, String name) {
        if (a == null ? b == null : a.equals(b)) pass++;
        else { fail++; System.out.println("FAIL " + name + ": got=" + a + " want=" + b); }
    }
    static void ok(boolean v, String name) {
        if (v) pass++; else { fail++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] args) throws Exception {
        // MIME 推断
        eq(RunableAttach.mimeOf("a/b/c.JPG"), "image/jpeg", "mime jpg");
        eq(RunableAttach.mimeOf("x.png"), "image/png", "mime png");
        eq(RunableAttach.mimeOf("x.webp"), "image/webp", "mime webp");
        eq(RunableAttach.mimeOf("x.pdf"), "application/pdf", "mime pdf");
        eq(RunableAttach.mimeOf("x.md"), "text/plain", "mime md");
        eq(RunableAttach.mimeOf("x.bin"), "application/octet-stream", "mime fallback");
        eq(RunableAttach.mimeOf(null), "application/octet-stream", "mime null");

        // 构造 Item（包私有构造）
        Class<?> itemCls = Class.forName("com.aigetway.tattoo7.provider.RunableAttach$Item");
        Constructor<?> ic = itemCls.getDeclaredConstructor();
        ic.setAccessible(true);
        Object it = ic.newInstance();
        for (String f : new String[]{"url", "name", "mediaType", "objectKey", "path"}) {
            Field fl = itemCls.getDeclaredField(f);
            fl.setAccessible(true);
            fl.set(it, "V_" + f);
        }
        Constructor<?> sc = Class.forName("com.aigetway.tattoo7.provider.RunableAttach$Src")
                .getDeclaredConstructor(String.class, String.class, String.class);
        sc.setAccessible(true);
        sc.newInstance("/a/x.jpg", "x.jpg", "");

        List<Object> items = new ArrayList<>();
        items.add(it);
        items.add(null);                       // 失败项必须被剔除

        Method toMeta = RunableAttach.class.getDeclaredMethod("toMetadataFiles", List.class);
        toMeta.setAccessible(true);
        org.json.JSONArray meta = (org.json.JSONArray) toMeta.invoke(null, items);
        eq(meta.length(), 1, "失败项被剔除");
        org.json.JSONObject o = meta.getJSONObject(0);
        eq(o.optString("url"), "V_url", "字段 url");
        eq(o.optString("name"), "V_name", "字段 name");
        eq(o.optString("mediaType"), "V_mediaType", "字段 mediaType");
        eq(o.optString("objectKey"), "V_objectKey", "字段 objectKey");
        eq(o.optString("path"), "V_path", "字段 path");
        eq(meta.toString().contains("planMode"), false, "files 元素不含 planMode");

        eq(RunableAttach.toMetadataFiles(null).length(), 0, "null 入参安全");
        eq(RunableAttach.toMetadataFiles(new ArrayList<>()).length(), 0, "空入参安全");

        System.out.println("PASS " + pass + "  FAIL " + fail);
        if (fail > 0) System.exit(1);
    }
}