package com.xaker.sshchecker;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.jcraft.jsch.*;

import java.io.InputStream;
import java.net.InetAddress;

public class MainActivity extends AppCompatActivity {

    private EditText ipInput;
    private Button checkButton;
    private ProgressBar progressBar;
    private TextView statusTextView, externalIpText;
    private ImageView statusIcon;

    private final String sshHost = "192.168.1.1";
    private final String sshUser = "root";
    private final String[] sshPasswords = {"Admin0101", "Admin012"};

    private boolean sshConnected = false;
    private String sshExternalIp = null;

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
        statusIcon = findViewById(R.id.statusIcon);

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
            statusTextView.setText("Подключение к SSH...");
            progressBar.setVisibility(View.VISIBLE);
            statusIcon.setVisibility(View.GONE);
            externalIpText.setText("Внешний IP:");
        }

        @Override
        protected String doInBackground(Void... voids) {
            for (String password : sshPasswords) {
                publishProgress("Пробуем пароль: " + password);
                try {
                    JSch jsch = new JSch();
                    Session session = jsch.getSession(sshUser, sshHost, 22);
                    session.setPassword(password);
                    session.setConfig("StrictHostKeyChecking", "no");
                    session.connect(5000);

                    Channel channel = session.openChannel("exec");
                    ChannelExec channelExec = (ChannelExec) channel;
                    channelExec.setCommand("ip addr show wwan0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1");

                    channelExec.setInputStream(null);
                    InputStream in = channelExec.getInputStream();
                    channelExec.connect();


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
                    session.disconnect();

                    String ipResult = output.toString().trim();
                    if (ipResult.matches("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")) {
                        return ipResult;
                    } else {
                        return "Ошибка: не удалось получить внешний IP";
                    }

                } catch (JSchException e) {
                    if (e.getMessage().toLowerCase().contains("auth fail")) {
                        continue;
                    }
                    return "Ошибка SSH: " + e.getMessage();
                } catch (Exception e) {
                    return "Ошибка: " + e.getMessage();
                }
            }
            return "Не удалось подключиться по SSH: все пароли не подошли";
        }

        @Override
        protected void onProgressUpdate(String... values) {
            statusTextView.setText(values[0]);
        }

        @Override
        protected void onPostExecute(String result) {
            progressBar.setVisibility(View.GONE);
            if (result != null && !result.toLowerCase().contains("ошибка")) {
                sshConnected = true;
                sshExternalIp = result;
                statusTextView.setText("SSH подключение успешно");
                externalIpText.setText("Внешний IP роутера: " + sshExternalIp);
                statusIcon.setImageResource(android.R.drawable.checkbox_on_background);

                retryHandler.removeCallbacks(retryRunnable);
                retryCount = 0;
            } else {
                sshConnected = false;
                sshExternalIp = null;
                statusTextView.setText(result);
                externalIpText.setText("Внешний IP:");
                statusIcon.setImageResource(android.R.drawable.ic_delete);

                if (retryCount < MAX_RETRIES) {
                    startRetryLoop();
                }
            }
            statusIcon.setVisibility(View.VISIBLE);
        }
    }

    private void startRetryLoop() {
        if (retryRunnable == null) {
            retryRunnable = () -> {
                retryCount++;
                new SSHConnectTask().execute();
            };
        }
        retryHandler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private class PingTask extends AsyncTask<String, String, Boolean> {

        // Список портов для проверки
        private final int[] portsToCheck = {80, 443, 22, 23, 8080};
        private int openPort = -1; // сюда запишем, какой порт открылся

        @Override
        protected void onPreExecute() {
            statusTextView.setText("Пинг...");
            progressBar.setVisibility(View.VISIBLE);
            statusIcon.setVisibility(View.GONE);
        }

        @Override
        protected Boolean doInBackground(String... params) {
            String ip = params[0];
            try {
                // 1. Сначала ICMP ping
                InetAddress inet = InetAddress.getByName(ip);
                if (inet.isReachable(2000)) {
                    return true;
                }

                // 2. Перебираем TCP-порты
                for (int port : portsToCheck) {
                    try (java.net.Socket socket = new java.net.Socket()) {
                        socket.connect(new java.net.InetSocketAddress(ip, port), 2000);
                        openPort = port; // нашли открытый порт
                        return true;
                    } catch (Exception ignored) {
                    }
                }

                return false; // ничего не открылось

            } catch (Exception e) {
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            progressBar.setVisibility(View.GONE);
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
            statusIcon.setVisibility(View.VISIBLE);
        }
    }
}




