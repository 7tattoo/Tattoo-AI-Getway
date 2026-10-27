package com.aigetway.tattoo7.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.aigetway.tattoo7.R;
import com.aigetway.tattoo7.core.App;

/** 短信验证码登录页（Loomy / 讯飞：无授权 URL，只能发码 + 填码）。 */
public class SmsLoginActivity extends Activity {

    private EditText phoneInput;
    private EditText codeInput;
    private TextView status;
    private Button sendBtn;
    private Button loginBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean finishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sms_login);

        phoneInput = findViewById(R.id.smsPhone);
        codeInput = findViewById(R.id.smsCode);
        status = findViewById(R.id.smsStatus);
        sendBtn = findViewById(R.id.smsSendBtn);
        loginBtn = findViewById(R.id.smsLoginBtn);

        sendBtn.setOnClickListener(v -> sendCode());
        loginBtn.setOnClickListener(v -> submitCode());
    }

    private void sendCode() {
        String phone = phoneInput.getText().toString().trim();
        if (phone.isEmpty()) {
            status.setText("请输入手机号");
            return;
        }
        status.setText("正在发送验证码...");
        sendBtn.setEnabled(false);
        new Thread(() -> {
            String msg;
            try {
                App.get().loomyProvider().sendSmsCode(phone);
                msg = "验证码已发送，请在 5 分钟内填写";
            } catch (Exception e) {
                msg = "发送失败: " + e.getMessage();
            }
            String f = msg;
            ui.post(() -> {
                status.setText(f);
                sendBtn.setEnabled(true);
                sendBtn.setText("重新发送");
            });
        }).start();
    }

    private void submitCode() {
        String phone = phoneInput.getText().toString().trim();
        String code = codeInput.getText().toString().trim();
        if (phone.isEmpty() || code.isEmpty()) {
            status.setText("请填写手机号和验证码");
            return;
        }
        status.setText("正在登录...");
        loginBtn.setEnabled(false);
        new Thread(() -> {
            boolean ok;
            String msg = "";
            try {
                ok = App.get().loomyProvider().submitSmsCode(phone, code);
                if (!ok) msg = "登录失败";
            } catch (Exception e) {
                ok = false;
                msg = "登录失败: " + e.getMessage();
            }
            boolean fok = ok;
            String fmsg = msg;
            ui.post(() -> {
                if (fok) {
                    if (finishing) return;
                    finishing = true;
                    status.setText("登录成功，正在返回...");
                    Intent back = new Intent();
                    back.putExtra("login_ok", true);
                    back.putExtra("provider", "loomy");
                    setResult(RESULT_OK, back);
                    ui.postDelayed(this::finish, 600);
                } else {
                    status.setText(fmsg);
                    loginBtn.setEnabled(true);
                }
            });
        }).start();
    }
}