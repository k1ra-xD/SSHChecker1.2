package com.xaker.sshchecker;

import android.view.View;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.navigation.NavigationView;
import com.jcraft.jsch.*;

import java.io.InputStream;
import java.net.InetAddress;

public class MainActivity extends AppCompatActivity {

    private EditText ipInput;
    private Button checkButton;
    private ProgressBar progressBar;
    private TextView statusTextView, externalIpText, localIpText;
    private ImageView statusIcon, menuButton;

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;

    private final String sshHost = "192.168.1.1";
    private final String sshUser = "root";
    private final String[] sshPasswords = {"Admin0101", "Admin012"};

    private Handler retryHandler = new Handler();
    private Runnable retryRunnable;
    private static final int RETRY_DELAY_MS = 5000;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipInput = findViewById(R.id.ipInput);
        checkButton = findViewById(R.id.checkButton);
        progressBar = findViewById(R.id.progressBar);
        statusTextView = findViewById(R.id.statusTextView);
        externalIpText = findViewById(R.id.externalIpText);
        localIpText = findViewById(R.id.localIpText);
        statusIcon = findViewById(R.id.statusIcon);
        menuButton = findViewById(R.id.menu_button);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);

        menuButton.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_test) {
                Toast.makeText(this, "Тестовый пункт нажат", Toast.LENGTH_SHORT).show();
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        new SSHConnectTask().execute();

        checkButton.setOnClickListener(v -> {
            String ipToPing = ipInput.getText().toString().trim();
            if (ipToPing.isEmpty()) {
                Toast.makeText(this, "Введите IP для проверки", Toast.LENGTH_SHORT).show();
                return;
            }
            new PingTask().execute(ipToPing);
        });
    }

    private class SSHConnectTask extends AsyncTask<Void, String, String[]> {
        @Override
        protected void onPreExecute() {
            statusTextView.setText("Подключение к SSH...");
            progressBar.setVisibility(View.VISIBLE);
            statusIcon.setVisibility(ImageView.GONE);
            externalIpText.setText("Внешний IP:");
            localIpText.setText("Локальный IP:");
        }

        @Override
        protected String[] doInBackground(Void... voids) {
            for (String password : sshPasswords) {
                publishProgress("Пробуем пароль: " + password);
                try {
                    JSch jsch = new JSch();
                    Session session = jsch.getSession(sshUser, sshHost, 22);
                    session.setPassword(password);
                    session.setConfig("StrictHostKeyChecking", "no");
                    session.connect(5000);

                    String externalIp = execCommand(session, "ip addr show wwan0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1");
                    String localIp = execCommand(session, "ip addr show usb0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1");

                    session.disconnect();
                    return new String[]{externalIp.trim(), localIp.trim()};

                } catch (JSchException e) {
                    if (e.getMessage().toLowerCase().contains("auth fail")) continue;
                    return new String[]{"Ошибка SSH: " + e.getMessage(), ""};
                } catch (Exception e) {
                    return new String[]{"Ошибка: " + e.getMessage(), ""};
                }
            }
            return new String[]{"Не удалось подключиться по SSH: все пароли не подошли", ""};
        }

        private String execCommand(Session session, String command) throws Exception {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            InputStream in = channel.getInputStream();
            channel.connect();

            byte[] tmp = new byte[1024];
            StringBuilder output = new StringBuilder();
            while (true) {
                while (in.available() > 0) {
                    int i = in.read(tmp, 0, 1024);
                    if (i < 0) break;
                    output.append(new String(tmp, 0, i));
                }
                if (channel.isClosed()) break;
                Thread.sleep(100);
            }
            channel.disconnect();
            return output.toString();
        }

        @Override
        protected void onProgressUpdate(String... values) {
            statusTextView.setText(values[0]);
        }

        @Override
        protected void onPostExecute(String[] result) {
            progressBar.setVisibility(ProgressBar.GONE);
            if (!result[0].toLowerCase().contains("ошибка") && !result[0].contains("Не удалось")) {
                externalIpText.setText("Внешний IP роутера: " + result[0]);
                localIpText.setText("Локальный IP USB: " + result[1]);
                statusIcon.setImageResource(R.drawable.ic_check);
            } else {
                statusTextView.setText(result[0]);
                statusIcon.setImageResource(R.drawable.ic_cross);
            }
            statusIcon.setVisibility(ImageView.VISIBLE);
        }
    }

    private class PingTask extends AsyncTask<String, String, Boolean> {

        private final int[] portsToCheck = {80, 443, 22, 23, 8080};
        private int openPort = -1;

        @Override
        protected void onPreExecute() {
            statusTextView.setText("Пинг...");
            progressBar.setVisibility(ProgressBar.VISIBLE);
            statusIcon.setVisibility(ImageView.GONE);
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String ip = params[0];
            try {
                InetAddress inet = InetAddress.getByName(ip);
                if (inet.isReachable(2000)) return true;

                for (int port : portsToCheck) {
                    try (java.net.Socket socket = new java.net.Socket()) {
                        socket.connect(new java.net.InetSocketAddress(ip, port), 2000);
                        openPort = port;
                        return true;
                    } catch (Exception ignored) {}
                }

                return false;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            progressBar.setVisibility(ProgressBar.GONE);
            if (success) {
                if (openPort != -1) {
                    statusTextView.setText("Доступен по TCP порту " + openPort);
                } else {
                    statusTextView.setText("Пинг успешен");
                }
                statusIcon.setImageResource(R.drawable.ic_check);
            } else {
                statusTextView.setText("Маршрутизатор недоступен");
                statusIcon.setImageResource(R.drawable.ic_cross);
            }
            statusIcon.setVisibility(ImageView.VISIBLE);
        }
    }
}
