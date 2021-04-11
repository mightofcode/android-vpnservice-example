package com.mocyx.basic_client;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.mocyx.basic_client.bio.BioTcpHandler;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends AppCompatActivity {


    public static AtomicLong downByte = new AtomicLong(0);
    public static AtomicLong upByte = new AtomicLong(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);


        setSupportActionBar(toolbar);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        TextView textView = findViewById(R.id.textView1);

        Thread t = new Thread(new UpdateText(textView));
        t.start();
    }

    static class UpdateText implements Runnable {

        TextView textView;

        UpdateText(TextView textView) {
            this.textView = textView;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(100);
                    textView.setText(String.format("up %dKB down %dKB", MainActivity.upByte.get() / 1024, MainActivity.downByte.get() / 1024));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }

        }
    }

    private static final String TAG = BioTcpHandler.class.getSimpleName();

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private static final int VPN_REQUEST_CODE = 0x0F;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            //waitingForVPNStart = true;
            startService(new Intent(this, LocalVPNService.class));
            //enableButton(false);
        }
    }

    private void startVpn() {

        Intent vpnIntent = VpnService.prepare(this);

        if (vpnIntent != null)
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        else
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
    }


    public void clickSwitch(View view) {
        System.out.println("hello");
        this.startVpn();


    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

    }


    public void clickHttp(View view) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long ts = System.currentTimeMillis();
                    //URL yahoo = new URL("https://www.google.com/");
                    URL yahoo = new URL("https://www.baidu.com/");
                    HttpURLConnection yc = (HttpURLConnection) yahoo.openConnection();

                    yc.setRequestProperty("Connection", "close");
                    yc.setConnectTimeout(30000);

                    yc.setReadTimeout(30000);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(yc.getInputStream()));
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        System.out.println(inputLine);
                    }

                    yc.disconnect();
                    in.close();
                    long te = System.currentTimeMillis();
                    Log.i(TAG, String.format("http cost %d", te - ts));

                    System.out.printf("http readline end\n");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();
    }

    public void clickStop(View view) {
        //
    }

    public static void displayStuff(String whichHost, InetAddress inetAddress) {
        System.out.println("--------------------------");
        System.out.println("Which Host:" + whichHost);
        System.out.println("Canonical Host Name:" + inetAddress.getCanonicalHostName());
        System.out.println("Host Name:" + inetAddress.getHostName());
        System.out.println("Host Address:" + inetAddress.getHostAddress());
    }

    public void clickDns(View view) {

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    long ts = System.currentTimeMillis();
                    String host = "www.baidu.com";

                    for (InetAddress inetAddress : InetAddress.getAllByName(host)) {
                        displayStuff(host, inetAddress);
                    }
                    long te = System.currentTimeMillis();
                    Log.i(TAG, String.format("dns cost %d", te - ts));

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();
    }
}
