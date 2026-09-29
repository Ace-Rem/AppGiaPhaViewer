package com.android.acerem.xemgp;
import android.app.*;import android.content.*;import android.os.Bundle;import com.android.acerem.xemgp.data.DataRepository;import com.android.acerem.xemgp.ui.*;
public class MainActivity extends Activity { protected void onCreate(Bundle b){super.onCreate(b);String fp=DataRepository.currentFingerprint(this);Intent i=new Intent(this,(fp!=null&&DataRepository.dataFile(this)!=null&&DataRepository.dataFile(this).isFile())?LoginActivity.class:ImportActivity.class);startActivity(i);finish();} }
