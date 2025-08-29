package com.xaker.sshchecker;

import android.view.View;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.material.navigation.NavigationView;
import com.jcraft.jsch.*;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
            int id = item.getItemId();
            if (id == R.id.nav_help) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Справка")
                        .setMessage("Это приложение позволяет:\n\n" +
                                "1. Подключаться к роутеру по SSH.\n" +
                                "2. Проверять доступность IP-адреса.\n" +
                                "3. Получать внешний IP роутера.\n\n" +
                                "Как пользоваться:\n" +
                                "- Введите IP в поле.\n" +
                                "- Нажмите 'Проверить'.\n" +
                                "- Результат отобразится в виде ✅ или ❌.\n" +
                                "- IP-адрес узнается автоматически.")
                        .setPositiveButton("ОК", null)
                        .show();
            }
            drawerLayout.closeDrawers();
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

    private class SSHConnectTask extends AsyncTask<Void, String, String> {
        @Override
        protected void onPreExecute() {
            statusTextView.setText("Подключение к роутеру...");
            progressBar.setVisibility(View.VISIBLE);
            statusIcon.setVisibility(ImageView.GONE);
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

                        String ipResult = execCommand(session,
                                "ip addr show mob1s1a1 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1").trim();

                        if (ipResult.isEmpty()) {
                            ipResult = execCommand(session,
                                    "ip addr show usb0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1").trim();
                        }

                        if (ipResult.isEmpty()) {
                            ipResult = execCommand(session,
                                    "ip addr show wan | grep 'inet ' | awk '{print $2}' | cut -d/ -f1").trim();
                        }

                        if (ipResult.isEmpty()) {
                            ipResult = execCommand(session,
                                    "ip addr show | grep 'inet ' | awk '{print $2}' | cut -d/ -f1 | " +
                                            "grep -E '^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01]))' | head -n 1").trim();
                        }

                        session.disconnect();

                        if (!ipResult.isEmpty()) {
                            return ipResult;
                        }

                    } catch (JSchException e) {
                        if (e.getMessage().toLowerCase().contains("auth fail")) continue;
                        if (attempt >= maxRetries) return "Ошибка SSH: " + e.getMessage();
                    } catch (Exception e) {
                        if (attempt >= maxRetries) return "Ошибка: " + e.getMessage();
                    }
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
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
            progressBar.setVisibility(ProgressBar.GONE);
            if (!result.toLowerCase().contains("ошибка") && !result.contains("Не удалось")) {
                externalIpText.setText("Внешний IP роутера: " + result);
                statusIcon.setImageResource(R.drawable.ic_check);
            } else {
                statusTextView.setText(result);
                statusIcon.setImageResource(R.drawable.ic_cross);
            }
            statusIcon.setVisibility(ImageView.VISIBLE);
        }
    }

    private class PingTask extends AsyncTask<String, String, Boolean> {

        private final int[] portsToCheck = {80, 443, 22, 23, 8080, 8443};
        private long pingTime = -1;

        @Override
        protected void onPreExecute() {
            statusTextView.setText("Пинг...");
            progressBar.setVisibility(View.VISIBLE);
            statusIcon.setVisibility(View.GONE);
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String ip = params[0];
            boolean reachable = false;

            try {
                long start = System.currentTimeMillis();
                Process process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 2 " + ip);
                int exitCode = process.waitFor();
                long end = System.currentTimeMillis();

                if (exitCode == 0) {
                    pingTime = end - start;
                    reachable = true;
                }

                ExecutorService executor = Executors.newFixedThreadPool(portsToCheck.length);
                List<Future<Boolean>> futures = new ArrayList<>();

                for (int port : portsToCheck) {
                    futures.add(executor.submit(() -> {
                        try (Socket socket = new Socket()) {
                            socket.connect(new InetSocketAddress(ip, port), 1000);
                            return true;
                        } catch (Exception ignored) {
                            return false;
                        }
                    }));
                }

                for (Future<Boolean> f : futures) {
                    if (f.get()) {
                        reachable = true; // хотя конкретный порт не сохраняем
                    }
                }
                executor.shutdownNow();

            } catch (Exception e) {
                return false;
            }

            return reachable;
        }

        @Override
        protected void onPostExecute(Boolean success) {
            progressBar.setVisibility(ProgressBar.GONE);
            if (success) {
                if (pingTime > 0) {
                    statusTextView.setText("Пинг успешен, время: " + pingTime + " мс");
                } else {
                    statusTextView.setText("Пинг успешен");
                }
                statusIcon.setImageResource(R.drawable.ic_check);
            } else {
                statusTextView.setText("Хост недоступен");
                statusIcon.setImageResource(R.drawable.ic_cross);
            }
            statusIcon.setVisibility(ImageView.VISIBLE);
        }
    }
}
