package com.xaker.sshchecker;

import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.navigation.NavigationView;
import com.jcraft.jsch.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MainActivity extends AppCompatActivity {

    private EditText ipInput;
    private Button checkButton;
    private ProgressBar progressBar;
    private TextView statusTextView, externalIpText;
    private ImageView statusIcon, menuButton;

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;

    private final String sshHost = "192.168.1.1";
    private final String sshUser = "root";
    private final String[] sshPasswords = {"Admin0101", "Admin012"};

    private Session existingSession; // Хранение сессии для Ping

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipInput = findViewById(R.id.ipInput);
        ipInput.setText("192.168.43.61");
        checkButton = findViewById(R.id.checkButton);
        progressBar = findViewById(R.id.progressBar);
        statusTextView = findViewById(R.id.statusTextView);
        externalIpText = findViewById(R.id.externalIpText);
        statusIcon = findViewById(R.id.statusIcon);
        menuButton = findViewById(R.id.menu_button);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);

        menuButton.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        navigationView.setNavigationItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_help) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Справка")
                        .setMessage("Это приложение позволяет:\n\n" +
                                "1. Подключаться к роутеру по SSH.\n" +
                                "2. Проверять доступность IP-адреса через SSH.\n" +
                                "3. Получать внешний IP роутера.\n\n" +
                                "Как пользоваться:\n" +
                                "- IP вводится автоматически.\n" +
                                "- Нажмите 'Проверить'.\n" +
                                "- Результат отобразится в виде ✅ или ❌.")
                        .setPositiveButton("ОК", null)
                        .show();
            }
            drawerLayout.closeDrawers();
            return true;
        });

        new SSHConnectTask().execute();

        checkButton.setOnClickListener(v -> {
            if (existingSession != null && existingSession.isConnected()) {
                String ipToPing = ipInput.getText().toString().trim();
                // 🟢 очищаем IP: оставляем только цифры и точки
                ipToPing = ipToPing.replaceAll("[^0-9.]", "");
                new PingSSHTask(existingSession).execute(ipToPing);
            } else {
                Toast.makeText(this, "SSH сессия не подключена", Toast.LENGTH_SHORT).show();
            }
        });

    }

    // ================= SSH CONNECT TASK =================
    private class SSHConnectTask extends AsyncTask<Void, String, String> {

        @Override
        protected void onPreExecute() {
            statusTextView.setText("Подключение к роутеру...");
            progressBar.setVisibility(android.view.View.VISIBLE);
            statusIcon.setVisibility(android.view.View.GONE);
            externalIpText.setText("Внешний IP:");
        }

        @Override
        protected String doInBackground(Void... voids) {
            int maxRetries = 3;
            int attempt = 0;

            while (attempt < maxRetries) {
                attempt++;
                for (String password : sshPasswords) {
                    publishProgress("Попытка " + attempt + " | Пробуем SSH пароль: " + password);
                    try {
                        JSch jsch = new JSch();
                        Session session = jsch.getSession(sshUser, sshHost, 22);
                        session.setPassword(password);
                        session.setConfig("StrictHostKeyChecking", "no");
                        session.connect(5000);

                        existingSession = session; // Сохраняем сессию для Ping

                        String ipResult = execCommand(session,
                                "ip addr show wwan0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1").trim();

                        if (ipResult.isEmpty()) {
                            ipResult = execCommand(session,
                                    "ip addr show | grep 'inet ' | awk '{print $2}' | cut -d/ -f1 | " +
                                            "grep -E '^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01]))' | head -n 1").trim();
                        }

                        if (!ipResult.isEmpty()) {
                            return ipResult;
                        }

                    } catch (Exception e) {
                        if (attempt >= maxRetries) return "Ошибка SSH: " + e.getMessage();
                    }
                }
            }
            return "Не удалось получить IP после " + maxRetries + " попыток";
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
        protected void onPostExecute(String result) {
            progressBar.setVisibility(android.view.View.GONE);
            if (!result.toLowerCase().contains("ошибка") && !result.contains("Не удалось")) {
                externalIpText.setText("Внешний IP роутера: " + result);
                statusIcon.setImageResource(R.drawable.ic_check);
            } else {
                statusTextView.setText(result);
                statusIcon.setImageResource(R.drawable.ic_cross);
            }
            statusIcon.setVisibility(android.view.View.VISIBLE);
        }
    }

    // ================= PING VIA SSH TASK =================
    private class PingSSHTask extends AsyncTask<String, Void, String> {

        private Session sshSession;
        private boolean success = false; // для определения какой значок показать

        public PingSSHTask(Session session) {
            this.sshSession = session;
        }

        @Override
        protected void onPreExecute() {
            statusTextView.setText("Проверка доступности (TCP → ARP → Route)...");
            progressBar.setVisibility(android.view.View.VISIBLE);
            statusIcon.setVisibility(android.view.View.GONE); // скрываем иконку пока идёт проверка
        }

        @Override
        protected String doInBackground(String... params) {
            if (sshSession == null || !sshSession.isConnected()) {
                return "Ошибка: SSH не подключён";
            }

            String targetIp = params[0];
            try {
                long start, end;

                // 1. TCP-пинг (порт 22 или 80)
                start = System.currentTimeMillis();
                String tcpResult = execCommand(sshSession,
                        "nc -z -w2 " + targetIp + " 22 || nc -z -w2 " + targetIp + " 80 && echo OK || echo FAIL");
                end = System.currentTimeMillis();
                if (tcpResult.contains("OK")) {
                    success = true;
                    return "Пинг прошёл " + (end - start) + " мс";
                }

                // 2. ARP-пинг
                start = System.currentTimeMillis();
                String arpResult = execCommand(sshSession,
                        "arp -n | grep " + targetIp + " | awk '{print $3}'");
                end = System.currentTimeMillis();
                if (!arpResult.trim().isEmpty()) {
                    success = true;
                    return "Пинг прошёл " + (end - start) + " мс";
                }

                // 3. Проверка маршрута
                start = System.currentTimeMillis();
                String routeResult = execCommand(sshSession,
                        "ip route get " + targetIp + " | head -n 1");
                end = System.currentTimeMillis();
                if (routeResult.contains(targetIp)) {
                    success = true;
                    return "Пинг прошёл " + (end - start) + " мс";
                }

                return "Узел недоступен";

            } catch (Exception e) {
                e.printStackTrace();
                return "Ошибка: " + e.getMessage();
            }
        }

        private String execCommand(Session session, String command) throws Exception {
            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setInputStream(null);
            InputStream in = channel.getInputStream();
            channel.connect();

            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            channel.disconnect();
            return output.toString();
        }

        @Override
        protected void onPostExecute(String result) {
            progressBar.setVisibility(android.view.View.GONE);
            statusTextView.setText(result);

            statusIcon.setVisibility(android.view.View.VISIBLE);
            if (success) {
                statusIcon.setImageResource(R.drawable.ic_check); // зелёная галочка
            } else {
                statusIcon.setImageResource(R.drawable.ic_cross); // красный крестик
            }
        }
    }









}
