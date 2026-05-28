package com.example.s_master;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkUtils {

    private static final String TAG = "NetworkUtils";
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 60000;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);

    public interface NetworkCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public static void fetchModels(String url, String apiKey, NetworkCallback<JSONArray> callback) {
        EXECUTOR.execute(() -> {
            try {
                HttpURLConnection conn = createConnection(url, "GET", apiKey);
                int code = conn.getResponseCode();
                String resp = readResponse(conn, code);

                if (code != 200) {
                    callback.onError("HTTP " + code + ": " + resp);
                    return;
                }

                JSONObject json = new JSONObject(resp);
                JSONArray data = json.getJSONArray("data");
                callback.onSuccess(data);
            } catch (Exception e) {
                Log.e(TAG, "Fetch models error", e);
                callback.onError(e.getMessage());
            }
        });
    }

    public static void callChatApi(String url, String apiKey, String body, NetworkCallback<String> callback) {
        EXECUTOR.execute(() -> {
            try {
                HttpURLConnection conn = createConnection(url, "POST", apiKey);
                conn.setDoOutput(true);
                writeBody(conn, body);

                int code = conn.getResponseCode();
                String resp = readResponse(conn, code);

                if (code != 200) {
                    callback.onError("HTTP " + code + ": " + resp);
                    return;
                }

                callback.onSuccess(resp);
            } catch (Exception e) {
                Log.e(TAG, "Chat API error", e);
                callback.onError(e.getMessage());
            }
        });
    }

    private static HttpURLConnection createConnection(String urlStr, String method, String apiKey) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        return conn;
    }

    private static String readResponse(HttpURLConnection conn, int code) throws Exception {
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) is = conn.getInputStream();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder resp = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                resp.append(line);
            }
            return resp.toString();
        }
    }

    private static void writeBody(HttpURLConnection conn, String body) throws Exception {
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
    }
}
