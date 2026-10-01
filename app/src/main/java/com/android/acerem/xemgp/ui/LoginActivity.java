package com.android.acerem.xemgp.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;
import com.android.acerem.xemgp.auth.AuthManager;
import com.android.acerem.xemgp.data.FamilyData;
import com.android.acerem.xemgp.data.ImageRepository;
import com.android.acerem.xemgp.util.Ui;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends Activity {
    public static final String EXTRA_ONLINE_SYNC = "online-sync";
    private EditText username, password;
    private CheckBox remember;
    private TextView error;
    private Button login;
    private boolean onlineSync;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.configure(this); Ui.applyBars(this); onlineSync=getIntent().getBooleanExtra(EXTRA_ONLINE_SYNC,false); build(); new android.os.Handler().postDelayed(this::restore, 160);
    }

    private void build() {
        LinearLayout root=Ui.column(this); root.setPadding(Ui.dp(this,26),Ui.dp(this,34),Ui.dp(this,26),Ui.dp(this,24)); root.setBackgroundColor(Ui.BG);
        TextView eyebrow=Ui.text(this,"MỘT NƠI ĐỂ NHỚ",12,Ui.ACCENT); eyebrow.setLetterSpacing(.16f); root.addView(eyebrow,new LinearLayout.LayoutParams(-1,Ui.dp(this,36)));
        root.addView(Ui.heading(this,"Chào mừng\ntrở về.",34),new LinearLayout.LayoutParams(-1,Ui.dp(this,104)));
        TextView sub=Ui.text(this,"Đăng nhập để tiếp tục khám phá câu chuyện của gia đình.",15,Ui.MUTED); sub.setLineSpacing(0,1.15f); root.addView(sub,new LinearLayout.LayoutParams(-1,Ui.dp(this,62)));
        username=field("Tên đăng nhập","Nhập tên đăng nhập",false); root.addView(username,new LinearLayout.LayoutParams(-1,Ui.dp(this,58)));
        password=field("Mật khẩu","Nhập mật khẩu",true); LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,Ui.dp(this,58)); pp.topMargin=Ui.dp(this,14); root.addView(password,pp);
        remember=new CheckBox(this); remember.setChecked(true); remember.setText("Ghi nhớ đăng nhập"); remember.setTextColor(Ui.TEXT); remember.setTextSize(14); remember.setButtonTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Ui.ACCENT,Ui.MUTED})); LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,Ui.dp(this,54)); rp.topMargin=Ui.dp(this,6); root.addView(remember,rp);
        error=Ui.text(this,"",13,Ui.COPPER); error.setMinHeight(Ui.dp(this,32)); root.addView(error);
        login=Ui.button(this,"Mở gia phả  →",true); login.setOnClickListener(v->submit()); root.addView(login,new LinearLayout.LayoutParams(-1,Ui.dp(this,54)));
        Button back=Ui.button(this,"←  Kiểm tra dữ liệu",false); back.setOnClickListener(v->{startActivity(new Intent(this,DataSyncActivity.class));finish();}); LinearLayout.LayoutParams backLp=new LinearLayout.LayoutParams(-1,Ui.dp(this,48)); backLp.topMargin=Ui.dp(this,8); root.addView(back,backLp);
        TextView foot=Ui.text(this,"Dữ liệu được giải mã hoàn toàn trên thiết bị. Không có máy chủ và không có Internet.",12,Ui.MUTED); foot.setGravity(Gravity.CENTER); foot.setPadding(8,Ui.dp(this,28),8,0); root.addView(foot,new LinearLayout.LayoutParams(-1,Ui.dp(this,80)));
        setContentView(root);
    }

    private EditText field(String label,String hint,boolean secret){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(Ui.MUTED);e.setTextColor(Ui.TEXT);e.setTextSize(15);e.setSingleLine(true);e.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),0);e.setBackground(Ui.outline(Ui.RAISED,Ui.LINE,Ui.dp(this,13)));if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);else e.setInputType(InputType.TYPE_CLASS_TEXT);return e;}
    private void restore(){executor.execute(()->{try{FamilyData d=AuthManager.restore(this);syncImagesThenOpen(d);}catch(Exception ignored){}});}
    private void submit(){String u=username.getText().toString().trim(),p=password.getText().toString();if(u.isEmpty()||p.isEmpty()){error.setTextColor(Ui.COPPER);error.setText("Vui lòng nhập đầy đủ thông tin để tiếp tục.");return;}login.setEnabled(false);error.setTextColor(Ui.ACCENT);error.setText("Đang mở dữ liệu…");executor.execute(()->{try{FamilyData d=AuthManager.signIn(this,u,p,remember.isChecked());syncImagesThenOpen(d);}catch(Exception e){runOnUiThread(()->{login.setEnabled(true);error.setTextColor(Ui.COPPER);error.setText("Thông tin đăng nhập chưa đúng hoặc dữ liệu không thể giải mã.");});}});}
    private void syncImagesThenOpen(FamilyData data){ImageRepository repository=ImageRepository.get(getApplicationContext());repository.prepareFamilyIndex(data);if(onlineSync)repository.syncForFamilyAsync(data);runOnUiThread(()->open(data));}
    private void open(FamilyData data){startActivity(new Intent(this,ViewerActivity.class));finish();}
    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
