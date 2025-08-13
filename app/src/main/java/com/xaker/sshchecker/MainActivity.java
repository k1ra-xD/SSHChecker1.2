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
    private TextView statusTextView, externalIpText, localIpText;
    private ImageView statusIcon;

    private final String sshHost = "192.168.1.1";
    private final String sshUser = "root";
    private final String[] sshPasswords = {"Admin0101", "Admin012"};

    private boolean sshConnected = false;
    private String sshExternalIp = null;
    private String sshLocalIp = null;

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
            statusIcon.setVisibility(View.GONE);
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

                    // Внешний IP
                    ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
                    channelExec.setCommand("ip addr show wwan0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1");
                    channelExec.setInputStream(null);
                    InputStream in = channelExec.getInputStream();
                    channelExec.connect();

                    byte[] tmp = new byte[1024];
                    StringBuilder externalOutput = new StringBuilder();
                    while (true) {
                        while (in.available() > 0) {
                            int i = in.read(tmp, 0, 1024);
                            if (i < 0) break;
                            externalOutput.append(new String(tmp, 0, i));
                        }
                        if (channelExec.isClosed()) break;
                        Thread.sleep(100);
                    }
                    channelExec.disconnect();

                    // Локальный IP (usb0)
                    channelExec = (ChannelExec) session.openChannel("exec");
                    channelExec.setCommand("ip addr show usb0 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1");
                    channelExec.setInputStream(null);
                    in = channelExec.getInputStream();
                    channelExec.connect();

                    StringBuilder localOutput = new StringBuilder();
                    while (true) {
                        while (in.available() > 0) {
                            int i = in.read(tmp, 0, 1024);
                            if (i < 0) break;
                            localOutput.append(new String(tmp, 0, i));
                        }
                        if (channelExec.isClosed()) break;
                        Thread.sleep(100);
                    }
                    channelExec.disconnect();
                    session.disconnect();

                    String externalIp = externalOutput.toString().trim();
                    String localIp = localOutput.toString().trim();

                    return new String[]{externalIp, localIp};

                } catch (JSchException e) {
                    if (e.getMessage().toLowerCase().contains("auth fail")) continue;
                    return new String[]{"Ошибка SSH: " + e.getMessage(), ""};
                } catch (Exception e) {
                    return new String[]{"Ошибка: " + e.getMessage(), ""};
                }
            }
            return new String[]{"Не удалось подключиться по SSH: все пароли не подошли", ""};
        }

        @Override
        protected void onProgressUpdate(String... values) {
            statusTextView.setText(values[0]);
        }

        @Override
        protected void onPostExecute(String[] result) {
            progressBar.setVisibility(View.GONE);
            if (!result[0].toLowerCase().contains("ошибка") && !result[0].contains("Не удалось")) {
                sshConnected = true;
                sshExternalIp = result[0];
                sshLocalIp = result[1];
                statusTextView.setText("SSH подключение успешно");
                externalIpText.setText("Внешний IP роутера: " + sshExternalIp);
                localIpText.setText("Локальный IP USB: " + sshLocalIp);
                statusIcon.setImageResource(R.drawable.ic_check);

                retryHandler.removeCallbacks(retryRunnable);
                retryCount = 0;
            } else {
                sshConnected = false;
                sshExternalIp = null;
                sshLocalIp = null;
                statusTextView.setText(result[0]);
                externalIpText.setText("Внешний IP:");
                localIpText.setText("Локальный IP:");
                statusIcon.setImageResource(R.drawable.ic_cross);

                if (retryCount < MAX_RETRIES) startRetryLoop();
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

        private final int[] portsToCheck = {80, 443, 22, 23, 8080};
        private int openPort = -1;

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
            progressBar.setVisibility(View.GONE);
            if (success) {
                if (openPort != -1) statusTextView.setText("Доступен по TCP порту " + openPort);
                else statusTextView.setText("Пинг успешен");
                statusIcon.setImageResource(R.drawable.ic_check);
            } else {
                statusTextView.setText("Маршрутизатор недоступен");
                statusIcon.setImageResource(R.drawable.ic_cross);
            }
            statusIcon.setVisibility(View.VISIBLE);
        }
    }
}
